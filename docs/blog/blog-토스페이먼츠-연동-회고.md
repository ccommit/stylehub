# 토스페이먼츠 결제를 붙이며 부딪힌 것들 — 설계 회고

> StyleHub 프로젝트에서 결제 도메인을 구현하면서 내가 실제로 막혔던 지점과, 그때마다 어떤 선택지들을 두고 무엇을 골랐는지를 순서대로 정리한 글이다. "이렇게 하는 게 정석이다"를 설명하려는 글이 아니라, 내가 왜 이런 결정을 했는지 스스로 다시 따라가 본 기록에 가깝다.

## 시작할 때 했던 착각

처음 결제를 붙일 때 나는 이걸 "토스 API를 호출하는 작업"이라고 생각했다. 공식 문서에 결제창 띄우고, successUrl 받고, 승인 API 쏘는 흐름이 잘 정리되어 있어서, 그대로 따라 치면 끝날 줄 알았다.

그런데 막상 코드를 짜다 보니 정작 시간을 가장 많이 쓴 부분은 토스를 호출하는 코드가 아니었다. "사용자가 결제창에서 5분 동안 멈춰 있으면?", "리다이렉트로 돌아온 금액을 그대로 믿어도 되나?", "같은 콜백이 두 번 들어오면?", "토스는 승인했는데 우리 서버가 그 순간 죽으면?" 같은, 정상 흐름에서 한 발만 벗어난 상황을 처리하는 코드였다.

결국 내가 내린 결론은, 결제는 **잘 돌아가게 만드는 것보다 잘못됐을 때 어떻게 되는지를 정해두는 게 본질**이라는 거였다. 그래서 구현을 시작하기 전에, 내가 무서워한 상황들을 먼저 적어두고 하나씩 "이건 무엇으로 막지?"를 정하는 식으로 진행했다.

내가 적어둔 무서운 상황들은 대략 이랬다.

- 사용자가 결제창에서 한참 멈춰 있는 동안 DB 커넥션이 잡혀 있으면 서버 전체가 느려진다
- 리다이렉트 URL의 amount를 브라우저에서 조작하면 5만원짜리를 1000원에 살 수 있다
- 결제 안 하고 창을 닫으면 차감된 재고가 묶인 채로 남는다
- 콜백이 중복 도착하면 같은 결제를 두 번 처리할 수도 있다
- 토스 응답을 기다리는 동안 커넥션을 물고 있으면 다른 API까지 같이 멈춘다
- 나중에 다른 PG를 추가하게 되면 결제 코드 전체를 뒤져야 한다

아래는 이 항목들을 하나씩 처리해 나간 과정이다.

---

## 주문과 결제를 한 메서드에 담으려다 멈춘 순간

가장 먼저 짠 건 주문 생성이었다. 처음엔 당연히 주문 생성과 결제 승인을 한 메서드 안에서 처리하려고 했다. 그게 제일 깔끔해 보였으니까.

그런데 토스 결제 흐름을 다시 보다가 멈췄다. 주문 생성과 결제 승인 **사이에 사용자의 행동이 끼어 있었다.** 카드를 고르고, 비밀번호를 넣고, 인증을 마치는 시간. 빠르면 30초, 망설이면 5분.

여기서 `@Transactional`이 메서드 전체를 감싸고 있으면 어떻게 되나 따져봤다. 사용자가 결제창에서 고민하는 그 수 분 동안 DB 커넥션이 계속 잡혀 있게 된다. HikariCP 기본 풀이 10개니까, 11번째 사용자부터는 커넥션을 못 잡고, 결제와 상관없는 상품 조회나 로그인까지 같이 멈춘다. 한 명이 결제창에서 망설이는 것 때문에 서비스 전체가 영향을 받는 구조라는 게 보였다.

그래서 흐름을 두 트랜잭션으로 끊었다.

```
[트랜잭션 1] placeOrder()      — 주문 생성 + 재고 차감 + Payment(READY) 저장
                              ↓ 커밋하고 커넥션 바로 반환
              ... 사용자가 결제창에서 인증 (수초 ~ 수분) ...
                              ↓
[트랜잭션 2] confirmPayment()  — 위변조 검증 + 토스 승인 + Payment(DONE)
```

이렇게 끊으니 사용자가 결제창에서 아무리 오래 끌어도 우리 커넥션 풀에는 영향이 없다. 인증을 마친 시점에 두 번째 트랜잭션이 새로 열리고, 토스 응답을 받은 뒤 짧게 닫힌다.

```java
// PaymentService.confirmPayment()
@Transactional
public PaymentResponse confirmPayment(String paymentKey, String pgOrderId, Integer tossAmount) {
    Payment payment = paymentRepository.findByOrderPgOrderIdWithLock(pgOrderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

    paymentValidator.validateApprovable(payment);
    paymentValidator.validateAmount(payment, tossAmount);

    paymentClientFactory.getClient("TOSS").confirmPayment(paymentKey, pgOrderId, tossAmount);

    return approvePayment(payment, paymentKey, tossAmount);
}
```

분리하고 나니 바로 다음 질문이 생겼다. **트랜잭션이 둘로 나뉘었는데, 이 둘이 서로를 어떻게 알아보지?**

---

## 두 트랜잭션을 잇는 끈을 무엇으로 할까 — pgOrderId

제일 단순한 답은 주문의 DB PK(`orderId`)를 토스에 그대로 넘기고, 콜백 때 그걸로 다시 찾는 거였다. 처음엔 그렇게 하려다가, 그 한 줄이 만드는 두 가지가 걸려서 멈췄다.

하나는 auto increment PK를 외부에 노출하는 문제다. 주문번호가 1000, 1001, 1002로 늘어나는 게 밖에서 보이면 "어제 1000번, 오늘 1100번 → 하루 100건"이 그대로 추정된다. 우리 거래량을 외부에서 읽을 수 있다는 뜻이라 찜찜했다.

다른 하나는 PK가 콜백 URL에 노출되면 추측이 쉬워진다는 점이었다.

그래서 외부에 보여줄 식별자를 따로 만들기로 했다.

```java
// Order.generatePgOrderId()
private static String generatePgOrderId() {
    String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    String uuid = UUID.randomUUID().toString().substring(0, 8);
    return "ORD-" + date + "-" + uuid;
}
```

`ORD-20260503-a1b2c3d4` 같은 형태로 만들었다. 날짜 prefix를 넣은 건 나중에 운영하면서 로그를 볼 때 "이 주문이 언제 거였지?"를 한눈에 보고 싶어서였다. UUID는 굳이 36자 전부 쓸 필요가 없어서 앞 8자만 잘랐다 — 하루 안에서 겹치지 않을 정도면 충분하다고 봤다. 토스에는 이 `pgOrderId`만 넘기고, 내부 처리에서는 `orderId`를 쓴다. 외부용 식별자와 내부용 식별자를 분리한 셈이다.

DB 쪽에서도 `pg_order_id`에 unique 제약을 걸어서, 혹시 같은 주문이 두 번 만들어지는 일을 한 번 더 막아뒀다.

---

## 가장 무서웠던 지점 — 돌아온 금액을 믿어도 되나

결제를 짜면서 제일 오래 들여다본 부분이 이거였다. successUrl 리다이렉트가 **사용자 브라우저를 거쳐서 온다**는 사실을 깨닫고 나서다.

내가 머릿속으로 그려본 공격 시나리오는 이랬다.

```
1. 사용자가 5만원 결제 인증을 마친다
2. 토스가 사용자 브라우저로 응답 → 브라우저가 우리 서버에 GET /payments/success?amount=50000
3. ★ 이 사이에 개발자 도구로 amount=1000으로 바꿔치기
4. 우리 서버가 1000원으로 토스 승인 요청 → 1000원만 결제
5. 사용자는 5만원짜리를 1000원에 가져간다
```

이게 무서웠던 이유는, 이 흐름에서 **토스도 우리도 아무 에러를 안 낸다**는 점이었다. 토스 입장에선 "사용자가 1000원 승인을 요청했고 카드도 1000원을 승인했다"는 완벽히 정상적인 흐름이다. 어디서도 빨간불이 안 켜진다.

그래서 방어 원리를 단순하게 잡았다. **금액의 기준점을 우리 DB에 두고, 클라이언트가 보낸 값과 비교하자.** 주문을 만들 때 서버가 계산한 결제 예정 금액(`requestedAmount`)을 Payment에 저장해두고, 콜백이 들어오면 그 값과 대조하는 식이다.

```java
// PaymentValidator.validateAmount()
public void validateAmount(Payment payment, Integer amount) {
    if (!payment.getRequestedAmount().equals(amount)) {
        throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }
}
```

세 줄짜리지만 결제에서 제일 중요한 코드라고 생각한다. DB의 `requestedAmount`는 사용자가 손댈 수 없는 값이라, 클라이언트가 amount를 뭘로 바꿔 보내든 여기서 걸린다. 나중에 토스 문서를 보니 여기도 "서버에서 반드시 금액을 검증하라"고 적혀 있어서, 방향이 맞았다는 걸 확인했다.

검증을 `PaymentService` 본문에 인라인으로 넣지 않고 `PaymentValidator`로 뺀 건 가독성 때문이었다. 위의 `confirmPayment()`를 다시 보면 "락으로 조회 → 승인 가능한가 → 금액 맞나 → 토스 호출 → 우리 DB 반영"이라는 흐름이 한 줄씩 읽힌다. 검증 로직이 본문에 섞여 있었으면 이 흐름이 묻혔을 것 같았다.

---

## PG가 토스 하나뿐인데 인터페이스를 둬도 되나

결제 클라이언트를 만들 때 잠깐 망설였다. 지금 우리는 토스만 쓴다. 그런데도 인터페이스부터 두는 게 맞나? "YAGNI"를 떠올리면 이건 과한 추상화다. PG를 바꿀 일이 실제로 자주 있는 것도 아니니까.

그런데 생각해보니 결제는 "거의 없다"가 아니라 "있을 수 있다"의 영역 같았다. 수수료가 오르거나, 정책이 바뀌거나, 카카오페이·네이버페이를 추가하는 일은 결제에선 드문 일이 아니다. 그때 가서 인터페이스를 뽑으려면 `PaymentService`에 흩어진 토스 호출을 전부 찾아 바꿔야 한다.

비용을 따져봤다. 지금 인터페이스를 두는 비용은 인터페이스 정의 한 줄과 팩토리 클래스 하나. 나중에 추출하는 비용은 호출 지점 전체 수정. 이 정도 차이면 미리 내고 가는 게 낫다고 판단했다.

```java
public interface PaymentClient {
    void confirmPayment(String paymentKey, String orderId, Integer amount);
    void cancelPayment(String paymentKey, String cancelReason, Integer cancelAmount);
    String getType();  // 팩토리에서 구현체를 식별하기 위한 PG 타입
}
```

```java
@Component
public class PaymentClientFactory {
    private final Map<String, PaymentClient> clients;

    // Spring이 PaymentClient 구현체를 모두 주입 → getType()으로 Map에 등록
    public PaymentClientFactory(List<PaymentClient> paymentClients) {
        this.clients = paymentClients.stream()
                .collect(Collectors.toMap(PaymentClient::getType, Function.identity()));
    }

    public PaymentClient getClient(String pgType) {
        PaymentClient client = clients.get(pgType);
        if (client == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return client;
    }
}
```

새 PG가 생기면 `PaymentClient`를 구현하는 클래스를 하나 만들고 `getType()`에 식별자만 넣으면 된다. Spring이 `List<PaymentClient>`로 모든 구현체를 자동 주입해주니 팩토리 코드는 안 바뀐다. 이때 "아, OCP가 말로만 듣던 게 이런 모양이구나"를 처음 체감했다.

---

## Order와 Payment가 서로를 부르다가 순환 참조에 빠진 일

이건 실제로 한 번 데였던 부분이다. 처음엔 `OrderService`가 `PaymentService`를 직접 주입받았다. 주문을 만들 때 `paymentService.createReady()`를 불러서 Payment를 만들었으니까. 그런데 결제가 승인되면 이번엔 `Order`를 PAID로 바꿔야 했고, 그래서 `PaymentService`도 `OrderService`를 주입받았다.

```
OrderService → PaymentService → OrderService → PaymentService ...
```

순환 참조였다. 부팅이 깨지기도 했고, 운 좋게 떠도 두 도메인이 단단히 엮여서 `OrderService` 하나 테스트하려면 `PaymentService`까지 끌고 와야 했다. 한쪽을 고치면 다른 쪽이 흔들렸다.

이걸 풀면서 이벤트 기반으로 바꿨다. 두 도메인이 서로 메서드를 직접 부르는 대신, 한쪽은 "이런 일이 일어났다"는 이벤트만 발행하고 다른 쪽이 구독한다.

```java
// OrderService.placeOrder() 내부
eventPublisher.publishEvent(new OrderPlacedEvent(savedOrder.getOrderId(), totalAmount, finalAmount));
```

```java
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final PaymentService paymentService;

    @EventListener
    public void on(OrderPlacedEvent event) {
        paymentService.createReady(event.orderId(), event.totalAmount(), event.finalAmount());
    }
}
```

이렇게 바꾸니 `OrderService`는 더 이상 `PaymentService`를 모른다. "주문이 생성됐다"는 사실만 알리고, 누가 그걸 받는지는 신경 쓰지 않는다.

여기서 한 가지 디테일을 챙겼다. **이벤트에 엔티티(`Order`)를 넣지 않고 primitive(`orderId` 등)만 넘겼다.** 엔티티를 넘기면 두 도메인이 다시 엔티티 타입에 묶이고, 영속성 컨텍스트를 넘나들면서 detached 문제가 생긴다. 받는 쪽에서 Order가 필요하면 `getReference()`로 프록시만 얻어 쓰게 했다.

```java
// PaymentService.createReady()
public void createReady(Long orderId, int totalAmount, int finalAmount) {
    Order orderRef = em.getReference(Order.class, orderId);
    paymentRepository.save(Payment.create(
            orderRef, "", "주문 결제", finalAmount, totalAmount, finalAmount
    ));
}
```

`getReference()`는 실제 SELECT를 안 날리는 프록시다. FK만 채우면 되는 자리라 이걸로 충분하고, 불필요한 조회 한 번을 아낀다. 작은 차이지만 대용량을 가정하는 프로젝트라 이런 절약이 쌓이면 의미가 있다고 봤다.

---

## 이벤트를 언제 실행할 것인가 — 한 번 데이고 정한 기준

이벤트로 바꾸고 나니 새로운 고민이 생겼다. **리스너를 트랜잭션 안에서 실행할까, 커밋 후에 실행할까?** 처음엔 다 똑같이 처리했다가, Redis 쪽에서 한 번 어긋나는 걸 보고 기준을 세웠다.

그 기준은 단순하다. 각 이벤트마다 "이 트랜잭션이 롤백되면 이 작업도 취소돼야 하나?"를 묻는 것.

```java
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final OrderService orderService;
    private final OrderPaymentTimeout orderPaymentTimeout;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        orderPaymentTimeout.registerTimeout(event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        orderPaymentTimeout.removeTimeout(event.orderId());
    }

    @EventListener
    public void onPaymentFailed(PaymentFailedEvent event) {
        orderService.cancelOrder(event.orderId());
    }
}
```

내가 정한 갈림길은 이거였다. **DB 작업끼리는 한 트랜잭션에 묶고, Redis 같은 외부 시스템은 커밋이 끝난 뒤에 건드린다.**

Redis는 트랜잭션을 모른다. 그래서 커밋 전에 Redis에 타이머를 등록했다가 DB가 롤백되면, DB에는 없는 주문의 타이머가 Redis에만 남는 유령 상태가 된다. 반대로 결제 실패 시 주문 취소 같은 DB 작업은 원자적으로 묶여야 하니 동기(`@EventListener`)로 같은 트랜잭션에 넣었다. 이 작은 어긋남이 나중에 새벽 알람으로 돌아온다는 걸 한 번 겪고 나서, 이벤트마다 시점을 따로 정하게 됐다.

---

## 같은 콜백이 두 번 들어오면 — 멱등성을 락으로

토스 콜백은 사용자 브라우저를 경유하기 때문에, 네트워크 재시도나 새로고침, 더블 클릭으로 같은 콜백이 여러 번 도착할 수 있다. 같은 `paymentKey`로 confirm이 두 번 들어오면 어떻게 되나 따져봤다.

토스 자체는 멱등성을 보장해서 두 번째 호출은 거부된다. 문제는 우리 DB 쪽이었다. 두 스레드가 같은 Payment를 동시에 조회하면 둘 다 "READY네, 승인하자"라고 판단할 수 있고, 한 번은 정상 승인되는데 다른 한 번은 토스에서 거부당하면서 우리 상태가 어긋날 여지가 있었다.

그래서 Payment를 **비관적 락으로 조회**하기로 했다. 먼저 들어온 스레드가 락을 잡으면 두 번째는 대기하고, 첫 번째가 status를 DONE으로 바꾸고 커밋하면 두 번째는 그 바뀐 상태를 보게 된다.

```java
// PaymentRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Payment p WHERE p.order.pgOrderId = :pgOrderId")
Optional<Payment> findByOrderPgOrderIdWithLock(@Param("pgOrderId") String pgOrderId);
```

```java
// PaymentValidator.validateApprovable()
public void validateApprovable(Payment payment) {
    if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.IN_PROGRESS) {
        throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
    }
}
```

흐름은 이렇게 흘러간다.

```
[스레드 A] 락 획득 → status=READY 확인 → 승인 → commit (status=DONE)
[스레드 B] 대기 ... → A 커밋 후 락 해제 → 락 획득 → status=DONE 확인
                                       → PAYMENT_ALREADY_PROCESSED로 거절
```

`PaymentIdempotencyTest`에서 같은 `pgOrderId`로 동시에 confirm을 보내 첫 번째만 성공하고 나머지는 거절되는 걸 확인했다. 비관적 락은 그동안 다른 요청을 막는 비용이 있지만, confirm은 주문당 한 번뿐이고 락을 잡는 시간도 토스 응답(보통 500ms 안쪽) 동안으로 짧아서 감당할 만하다고 봤다.

---

## 외부 API를 트랜잭션 안에 두는 건 금기인데, 일부러 어겼다

여기는 내가 한참 고민하다가 일부러 "하지 말라는 걸" 한 부분이다.

`confirmPayment()`는 `@Transactional`이 붙어 있고, 그 안에서 토스 API를 호출한다. 원래 외부 API 호출은 트랜잭션 밖에서 해야 한다고 배웠다. 응답이 느려지면 그동안 커넥션을 물고 있고, 외부가 죽으면 우리 트랜잭션도 같이 죽으니까.

그런데 이 경우엔 안에 두는 게 맞다고 판단했다. 이유는 토스 승인에서 **"호출은 했는데 응답을 못 받은" 상태가 제일 위험**하기 때문이다.

토스 호출을 트랜잭션 밖에서 하고 응답을 받은 뒤 별도 트랜잭션으로 DB를 업데이트한다고 해보자. 두 작업 사이에서 서버가 죽거나 예외가 나면, 토스는 승인했는데 우리 DB는 READY로 남는다. 그러면 30분 뒤 자동 취소가 돈다 — **사용자는 돈이 빠져나갔는데 주문은 취소되는** 최악의 상황이다.

이걸 막으려면 "토스 승인"과 "우리 DB 변경"이 한 묶음으로 같이 성공하거나 같이 실패해야 한다. 트랜잭션 안에서 토스를 호출하면, 토스가 예외를 던지든 우리 DB 변경이 실패하든 전부 같이 롤백된다. 둘 중 하나라도 실패하면 DB는 깔끔하게 READY로 남고 사용자는 다시 시도하면 된다.

이건 분명한 트레이드오프다. 응답 시간 동안 커넥션을 점유한다. 다만 결제 승인은 빈도가 낮고 토스 응답이 보통 500ms 안쪽으로 와서 감당 가능하다고 봤고, 더 중요하게는 **결제는 빠른 것보다 틀리지 않는 게 먼저**라고 생각했다. 그래서 이 한 곳에서는 정합성을 응답 시간보다 위에 뒀다.

---

## 결제 안 하고 사라진 사용자 — 묶인 재고를 어떻게 풀까

주문을 만들면 재고를 먼저 차감한다. 그런데 사용자가 결제를 안 하고 창을 닫으면, 차감된 재고가 묶인 채로 남는다. 30분이고 한 시간이고 다른 사람이 그 상품을 못 산다.

그래서 미결제 주문을 일정 시간 뒤 자동으로 풀어주는 장치가 필요했다. Redis ZSET에 만료 시각을 score로 넣어두고, 스케줄러가 주기적으로 만료된 주문을 찾아 취소 + 재고 복구하는 방식으로 짰다.

```java
public void registerTimeout(Long orderId) {
    double expireAt = System.currentTimeMillis() + TIMEOUT_MILLIS; // 현재 + 30분
    redisTemplate.opsForZSet().add("order:timeout", String.valueOf(orderId), expireAt);
}
```

여기서 다중 서버를 가정하니 문제가 하나 보였다. 만료된 주문을 조회(ZRANGEBYSCORE)하고 삭제(ZREM)하는 걸 두 명령으로 나누면, 서버 A와 B가 같은 주문을 동시에 집어서 중복 취소할 수 있다. 그래서 조회와 삭제를 Lua 스크립트로 묶어 원자적으로 처리했다.

```lua
local orders = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
if #orders > 0 then
    redis.call('ZREM', KEYS[1], unpack(orders))
end
return orders
```

Redis에서 Lua 스크립트는 실행 중 다른 명령이 못 끼어든다. 그래서 "조회한 주문 = 삭제한 주문"이 보장되고, 한 주문은 한 서버만 가져간다.

그런데 여기서 한 발 더 의심했다. **Redis가 죽으면?** 타이머 자체가 등록 안 된 주문은 영영 PENDING으로 남는다. 그래서 DB를 직접 훑는 보정 스케줄러를 따로 뒀다.

```java
@Scheduled(fixedDelay = 3600000)
public void compensateOrphanedOrders() {
    LocalDateTime expiredTime = LocalDateTime.now().minusMinutes(30);
    List<Order> orphanedOrders = orderRepository.findExpiredOrders(
            OrderStatus.PENDING, expiredTime, PageRequest.of(0, BATCH_SIZE)
    );
    for (Order order : orphanedOrders) {
        orderService.cancelOrder(order.getOrderId());
    }
}
```

Redis 스케줄러가 1차 방어, DB 보정 스케줄러가 2차 방어다. Redis가 정상일 땐 1분 단위로 빠르게 처리하고, Redis에 문제가 생겨도 DB 보정이 한 시간 안에 결국 정리한다. 인프라 하나에 정합성을 통째로 거는 게 불안해서 안전망을 이중으로 깔았다.

---

## 결제 실패와 취소 — 이벤트 구조가 여기서 빛을 봤다

사용자가 결제창에서 취소를 누르거나 인증에 실패하면 토스가 `failUrl`로 리다이렉트한다. 이때 할 일은 Payment를 ABORTED로 바꾸고, 주문을 취소하고, Redis 타이머를 제거하는 것이다.

```java
// PaymentService.handlePaymentFailure()
@Transactional
public void handlePaymentFailure(String pgOrderId) {
    Payment payment = findPaymentByOrderId(pgOrderId);

    payment.abort();
    eventPublisher.publishEvent(new PaymentFailedEvent(payment.getOrder().getOrderId()));
}
```

여기서 `PaymentService`가 `OrderService.cancelOrder()`를 직접 부르지 않는다는 게 포인트다. 이벤트만 발행하고, 앞에서 도메인을 분리하느라 만든 구조가 그대로 일을 한다. **결제 실패라는 한 사건이 → 결제 abort + 주문 취소 + Redis 타이머 제거로 자연스럽게 갈라진다.** 각 도메인은 자기 일만 한다.

이 지점에서 도메인 분리가 단순히 예쁜 코드를 위한 게 아니라 **변경에 대한 보험**이라는 걸 실감했다. 나중에 결제 실패 시 알림 발송을 추가하게 되면, `PaymentFailedEvent`를 구독하는 리스너를 하나 더 만들면 끝이다. `PaymentService`도 `OrderService`도 손댈 필요가 없다.

결제 취소/환불은 토스에서 하나의 API로 처리된다. `cancelAmount`를 안 넣으면 전액, 넣으면 부분 취소다. 부분 취소는 여러 번 가능해서 잔액을 관리해야 했는데, 잔액이 0이 되면 전액 취소 상태로 자동 전환되게 했다.

```java
public void cancelPartial(Integer amount, String reason) {
    this.cancelAmount = (this.cancelAmount != null ? this.cancelAmount : 0) + amount;
    this.balanceAmount = this.balanceAmount - amount;
    this.cancelReason = reason;
    this.status = (this.balanceAmount == 0) ? PaymentStatus.CANCELED : PaymentStatus.PARTIAL_CANCELED;
}
```

그리고 "취소가 언제 가능한가"라는 비즈니스 규칙은 서비스에 if-else로 박지 않고 `CancelPolicy`로 뺐다. 배송 중이면 취소 불가, 배송 완료 후 7일 지나면 환불 불가 같은 규칙인데, 이런 건 자주 바뀐다고 봤다. 기간이 7일에서 14일로 바뀌거나 VIP는 30일 같은 정책이 붙으면 Policy 한 곳만 고치면 되도록 분리해뒀다.

```java
@Component
public class CancelPolicy {

    private static final int REFUND_DAYS = 7;

    public void validate(Order order) {
        DeliveryStatus deliveryStatus = order.getDeliveryStatus();

        if (deliveryStatus == DeliveryStatus.SHIPPING) {
            throw new BusinessException(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
        }
        if (deliveryStatus == DeliveryStatus.DELIVERED) {
            LocalDateTime refundDeadline = order.getUpdatedAt().plusDays(REFUND_DAYS);
            if (LocalDateTime.now().isAfter(refundDeadline)) {
                throw new BusinessException(ErrorCode.REFUND_PERIOD_EXPIRED);
            }
        }
    }
}
```

---

## 시크릿 키 — 코드에 박았다가 큰일 날 뻔한 부분

토스 호출에는 Secret Key가 필요하다. 이걸 코드에 그대로 적으면 GitHub에 올라가는 순간 누구나 우리 결제를 만질 수 있다. 흔한 보안 사고 1순위라고 들어서, 처음부터 외부 설정으로 분리했다.

```java
@Component
@ConfigurationProperties(prefix = "toss.payments")
@Getter
@Setter
public class TossPaymentProperties {
    private String secretKey;
    private String confirmUrl;
    private String cancelUrl;
}
```

`@Value`로 한 줄씩 읽지 않고 `@ConfigurationProperties` 객체로 묶은 건, 토스 관련 설정이 한 클래스에 모여 있는 게 찾고 바꾸기 편해서다. 지금은 테스트 키라 properties에 그대로 있지만, 운영 키는 환경 변수나 Secrets Manager로 주입할 자리다.

토스 인증은 Secret Key를 Base64로 인코딩한 Basic Auth다.

```java
private String encodeSecretKey() {
    return Base64.getEncoder().encodeToString(
            (tossProperties.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8)
    );
}
```

`secretKey + ":"` 패턴은 Basic Auth 표준이다. 사용자명 자리에 키, 비밀번호 자리는 빈 문자열. 이건 토스 문서를 따른 거지만 표준 형식이라 다른 PG로 가도 비슷할 것 같아서, 메서드 이름을 "토스 인증 헤더"가 아니라 `createAuthHeaders()`로 두루뭉술하게 뒀다.

호출 코드 자체는 의외로 단순했는데, 예외 처리에서 한 가지를 신경 썼다.

```java
try {
    restTemplate.postForEntity(tossProperties.getConfirmUrl(), new HttpEntity<>(body, headers), String.class);
    log.info("토스 결제 승인 성공: orderId={}", orderId);
} catch (HttpClientErrorException e) {
    log.error("토스 결제 승인 실패: orderId={}, status={}, body={}", orderId, e.getStatusCode(), e.getResponseBodyAsString());
    throw new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED);
} catch (RestClientException e) {
    log.error("토스 결제 승인 실패: orderId={}, error={}", orderId, e.getMessage());
    throw new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED);
}
```

예외를 두 단계로 잡은 이유는 로그를 다르게 남기고 싶어서였다. `HttpClientErrorException`은 4xx(요청이 잘못됨)라 응답 body까지 찍고, `RestClientException`은 네트워크·타임아웃 같은 통신 실패라 메시지를 찍는다. 나중에 장애가 났을 때 "토스가 거절한 건지, 우리 네트워크가 문제인지"를 로그만 보고 구분하려고 한 거다. 그리고 둘 다 우리 도메인 예외(`BusinessException`)로 변환해서, 외부 시스템의 예외가 컨트롤러까지 그대로 올라가지 않게 경계에서 막았다.

---

## 돌아보며

결제를 다 붙이고 나서 든 생각은, 결국 이 작업의 대부분은 토스를 호출하는 코드가 아니라 **"이게 잘못되면 무엇이 무너지는가"에 답하는 코드**였다는 거다. 처음에 적어둔 무서운 상황들이 그대로 설계의 뼈대가 됐다.

- 사용자가 결제창에서 멈추면 → 주문/결제 트랜잭션 분리
- DB PK가 새어나가면 → UUID 기반 pgOrderId
- 금액이 조작되면 → DB requestedAmount와 비교
- PG를 바꿔야 하면 → PaymentClient 인터페이스 + 팩토리
- 도메인이 서로를 부르면 → 이벤트 기반 통신
- 롤백됐는데 외부 호출이 남으면 → AFTER_COMMIT
- 콜백이 중복 도착하면 → 비관적 락
- 토스 호출 중 서버가 죽으면 → 토스 호출과 DB 변경을 한 트랜잭션
- 결제 안 하고 사라지면 → Redis ZSET + DB 보정 이중 안전망
- 시크릿 키가 노출되면 → 외부 설정 분리

각 선택에는 비용이 따랐고, 어떤 건 일부러 "정석"을 어기기도 했다(외부 API를 트랜잭션 안에 둔 것처럼). 중요한 건 무엇을 막고 무엇을 의식적으로 허용할지 매번 따져본 과정 자체였다고 생각한다. 토스 문서가 답해주는 건 "어떻게 호출하는가"까지였고, "잘못됐을 때 어떻게 회복하는가"는 결국 내가 직접 정해야 했다. 이 부분을 고민한 흔적이 이 글에 남아 있길 바란다.
