# 토스페이먼츠 결제 연동 — "실패 시나리오 카탈로그" 기반 설계 기록

## 왜 결제 연동을 "실패 시나리오부터 명시화"하는 방식으로 풀었나

토스페이먼츠 공식 문서는 정상 경로(API 호출)를 매우 잘 정리해준다. 그러나 production-grade 결제 시스템의 작업 비중을 분석하면, **공식 문서가 답해주는 영역은 전체의 20%에 불과**하다. 나머지 80%는 다음과 같은 비정상 시나리오에 대한 답이다.

| 시나리오 | 차단하지 않을 때의 결과 |
|---------|------------------|
| 사용자가 결제창에서 수 분 동안 멈춤 | DB 커넥션 점유 → 시스템 전체 가용성 저하 |
| amount 파라미터 클라이언트 조작 | 부정 결제, 매출 손실 |
| 사용자가 결제창을 닫고 사라짐 | 재고 잠김으로 다른 사용자가 구매 불가 |
| 토스 콜백 네트워크 재시도로 중복 도착 | 이중 결제 처리, 멱등성 깨짐 |
| 토스 API 응답 1~2초 동안 DB 커넥션 점유 | 풀 고갈로 결제 외 API 동반 정지 |
| 나중에 다른 PG로 전환 필요 | 결제 도메인 전체 if-else 미궁 |

이 시나리오 카탈로그가 곧 설계 명세다. 즉 **"토스가 시키는 대로 호출한다"** 가 아니라 **"각 비정상 시나리오를 어떤 메커니즘이 차단하는가"** 가 결정의 출발점이었다.

이 프로젝트의 결제 도메인은 **시스템의 모든 설계 결정이 모이는 종착역**이다 — 트랜잭션 범위, 동시성 제어, 도메인 분리, 외부 API 처리, 보안, 운영 정합성이 한 도메인에서 동시에 만난다. 인증·상품 등 다른 도메인에서 미뤘던 질문들이 여기서 한꺼번에 답해진다.

이 글은 그 시나리오 카탈로그에 따라 메커니즘을 조합한 의사결정의 기록이다 — **"어떻게 호출했는가"** 보다 **"어떤 실패를 어떤 메커니즘으로 차단했는가"** 에 무게를 둔다.

---

## 본문

### 주문과 결제를 두 트랜잭션으로 나눈 이유

처음에는 주문과 결제를 한 메서드 안에서 처리하려고 했다. 가장 깔끔한 모양이니까. 그런데 토스 결제 흐름을 보고 멈췄다. **주문 생성과 결제 승인 사이에 사용자의 행동이 끼어있었다.** 결제창에서 카드를 고르고, 비밀번호를 입력하고, 인증을 완료하는 시간 — 30초일 수도 있고 5분일 수도 있다.

만약 이 시간 동안 `@Transactional`이 메서드 전체를 감싸고 있다면, **사용자가 결제창에서 고민하는 수 분 동안 DB 커넥션이 잡혀있게 된다.** HikariCP 기본 풀이 10개. 11번째 사용자부터 커넥션을 못 잡고, 상품 조회도 로그인도 같이 멈춘다. 사용자가 결제창에서 망설이는 것 때문에 전체 서비스가 죽는 구조.

그래서 흐름을 두 단계로 분리했다.

```
[트랜잭션 1] placeOrder()           — 주문 생성 + 재고 차감 + Payment(READY) 저장
                                    ↓ 커밋 후 커넥션 즉시 반환
              ... 사용자가 결제창에서 인증 (수초 ~ 수분) ...
                                    ↓
[트랜잭션 2] confirmPayment()       — 위변조 검증 + 토스 승인 API + Payment(DONE)
```

두 트랜잭션이 어떻게 서로를 알아보느냐는 다음 절의 주제로 이어지지만, 먼저 분리 자체가 가져오는 이점이 있다. 사용자가 결제창에서 5분을 끌어도 우리 서버의 커넥션 풀에는 영향이 없다. 사용자가 결제 인증을 마친 시점에 두 번째 트랜잭션이 새로 열리고, 토스 응답을 받은 뒤 짧게 닫힌다.

```java
// 실제 코드 — PaymentService.confirmPayment()
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

이 메서드 한 덩이가 트랜잭션 2다. 사용자의 인증 시간은 이 메서드 진입 전에 이미 끝나있다.

### pgOrderId — DB PK를 외부에 노출하지 않는다

두 트랜잭션이 분리되면 "둘 사이를 무엇으로 연결하지?"가 문제가 된다. 가장 단순한 답은 `orderId`(DB PK)를 토스에 그대로 넘기는 것. 그런데 이 한 줄이 두 가지 위험을 만든다.

**첫째, auto increment PK를 외부에 노출하면 사업 정보가 새어나간다.** "어제 주문번호 1000번이었는데 오늘 1100번이네 → 하루 100건"이 외부에서 그대로 보인다. 경쟁사가 우리 거래량을 추정할 수 있다는 뜻이다.

**둘째, PK 추측 공격이 가능해진다.** 결제 콜백 URL에 PK가 노출되면 "1번 주문은 ㅇㅇㅇ씨 거고 2번은…"식으로 패턴을 만들 수 있다.

그래서 별도의 외부용 식별자 `pgOrderId`를 만들었다.

```java
// 실제 코드 — Order.generatePgOrderId()
private static String generatePgOrderId() {
    String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    String uuid = UUID.randomUUID().toString().substring(0, 8);
    return "ORD-" + date + "-" + uuid;
}
```

`ORD-20260503-a1b2c3d4` 같은 형태. 날짜 prefix는 운영 디버깅 시 "이 주문이 언제 거였지?"를 한눈에 볼 수 있도록 의도적으로 넣었다. UUID 8자리는 같은 날 내에 충돌하지 않을 만큼 충분한 엔트로피를 주면서도 길이가 적당하다. 토스에는 이 `pgOrderId`만 넘기고, DB 내부 처리에서는 `orderId`를 쓴다. **외부 식별자와 내부 식별자를 명확히 분리**한 것이다.

토스 콜백이 돌아올 때도 `pgOrderId`로 Payment를 조회한다.

```java
// 실제 코드 — PaymentRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Payment p WHERE p.order.pgOrderId = :pgOrderId")
Optional<Payment> findByOrderPgOrderIdWithLock(@Param("pgOrderId") String pgOrderId);
```

`pg_order_id` 컬럼은 `unique`로 잡아둬서, 같은 주문이 두 번 만들어지지 않도록 DB 제약으로도 한 번 더 막았다.

### 금액 위변조 방어 — 클라이언트가 보낸 금액을 절대 신뢰하지 않는다

토스 결제 흐름에서 가장 위험한 지점은 **successUrl 리다이렉트가 사용자 브라우저를 경유한다**는 것이다.

```
1. 사용자가 5만원 결제 인증 완료
2. 토스가 사용자 브라우저로 응답 → 브라우저가 우리 서버에 GET /payments/success?amount=50000 호출
3. ★ 이 사이에 사용자가 개발자 도구로 amount=1000으로 변조 가능
4. 우리 서버가 1000원으로 토스 승인 요청 → 1000원만 결제됨
5. 사용자는 5만원짜리 상품을 1000원에 받는다
```

이 시나리오에서 **토스도 우리 서버도 어떤 검증도 실패하지 않는다.** 토스 입장에서는 "사용자가 1000원 승인을 요청했고 카드도 1000원 결제를 승인했다"는 정상 흐름이다. 그래서 더 무섭다.

방어의 원리는 단순하다. **금액의 단일 신뢰원(single source of truth)을 DB에 두고, 클라이언트가 보낸 금액과 비교한다.** 주문 생성 시점에 서버가 계산한 결제 예정 금액(`requestedAmount`)을 Payment 엔티티에 저장해두고, 토스 콜백이 들어왔을 때 이 값과 비교한다.

```java
// 실제 코드 — PaymentValidator.validateAmount()
public void validateAmount(Payment payment, Integer amount) {
    if (!payment.getRequestedAmount().equals(amount)) {
        throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }
}
```

세 줄짜리 메서드지만 결제 시스템에서 가장 중요한 세 줄 중 하나다. **DB에 저장된 `requestedAmount`는 사용자가 변조할 수 없는 값**이다. 클라이언트가 amount를 무엇으로 바꿔서 보내든, 이 검증 한 줄이 마지막 방어선이 된다. 토스 공식 문서에서도 "서버에서 반드시 금액을 검증하라"고 명시하고 있다.

검증 로직을 `PaymentService` 본문에 인라인하지 않고 `PaymentValidator`라는 별도 컴포넌트로 분리한 이유는, **서비스 메서드의 시각적 가독성** 때문이다. 위에서 본 `confirmPayment()`를 보면 "락으로 조회 → 승인 가능한가? → 금액이 맞는가? → 토스 호출 → 우리 DB 반영"이라는 흐름이 한눈에 읽힌다. 검증 로직이 본문에 섞여있었다면 이 흐름이 묻혔을 것이다.

### 전략 패턴 — PG가 토스 하나뿐이라도 인터페이스를 둔다

지금 우리는 토스만 쓴다. 그런데 결제 클라이언트를 만들 때 처음부터 인터페이스를 뒀다.

```java
// 실제 코드 — PaymentClient 인터페이스
public interface PaymentClient {
    void confirmPayment(String paymentKey, String orderId, Integer amount);
    void cancelPayment(String paymentKey, String cancelReason, Integer cancelAmount);
    String getType();  // 팩토리에서 구현체를 식별하기 위한 PG사 타입
}
```

```java
// 실제 코드 — TossPaymentClient
@Component
public class TossPaymentClient implements PaymentClient {
    @Override
    public String getType() {
        return "TOSS";
    }
    // confirmPayment(), cancelPayment() 구현 ...
}
```

"YAGNI(You Aren't Gonna Need It)" 원칙에 따르면 이건 과한 추상화다. 실제로 PG를 바꿀 일은 거의 없으니까. 그런데 **결제는 "거의 없다"가 아니라 "있을 수 있다"의 영역**이다. PG사 정책 변경, 수수료 인상, 신규 PG 도입(카카오페이, 네이버페이 등 추가) — 결제 비즈니스에선 흔한 일이다. 그때 가서 인터페이스를 추출하려면 `PaymentService`의 모든 토스 호출 지점을 찾아 바꿔야 한다.

지금 인터페이스를 두면 미래의 변경 비용이 한 자리(구현체 추가 + 팩토리 등록)로 줄어든다. 이 추상화의 비용은 인터페이스 정의 한 줄과 팩토리 한 클래스. 이 정도라면 미리 내고 가는 게 맞다고 판단했다.

```java
// 실제 코드 — PaymentClientFactory
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

새 PG가 추가되면 `PaymentClient`를 구현하는 클래스 하나 만들고 `getType()`에 식별자만 넣으면 된다. **Spring DI가 `List<PaymentClient>`로 모든 구현체를 자동 주입**해주기 때문에 팩토리 코드는 한 글자도 안 바뀐다. "확장에는 열려있고 수정에는 닫혀있다"는 OCP의 가장 자연스러운 형태다.

### Order와 Payment의 순환 참조 — 이벤트로 끊었다

처음에는 `OrderService`가 `PaymentService`를 직접 주입했다. 주문 생성 시 `paymentService.createReady()`를 호출해 Payment를 만들었으니까. 그런데 결제 승인 후에는 `Order`를 PAID로 바꿔야 했다. 그래서 `PaymentService`도 `OrderService`를 주입했다. 이렇게.

```
OrderService → PaymentService → OrderService → PaymentService ...
```

순환 참조다. Spring이 `@Autowired` 시점에 한쪽이 다른 쪽을 못 찾아서 부팅이 깨지거나, 운 좋게 부팅되더라도 **두 도메인이 강결합**된다. `OrderService`를 테스트하려면 `PaymentService`도 같이 mock해야 하고, 한쪽 변경이 다른 쪽에 즉시 전파된다.

해법은 **이벤트 기반 통신**이다. 두 도메인이 직접 메서드를 호출하는 대신, 이벤트를 발행하고 다른 도메인이 그걸 구독한다.

```java
// 실제 코드 — OrderService.placeOrder() 내부
eventPublisher.publishEvent(new OrderPlacedEvent(savedOrder.getOrderId(), totalAmount, finalAmount));
```

```java
// 실제 코드 — PaymentEventListener
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

`OrderService`는 이제 `PaymentService`를 모른다. "주문이 생성됐다"는 사실만 이벤트로 알리고, 누가 그 이벤트를 받는지는 관심 밖이다. **반대 방향도 마찬가지다.** 결제가 승인되면 `PaymentService`는 `PaymentApprovedEvent`만 발행하고, `OrderEventListener`가 그걸 받아서 Redis 타임아웃을 제거한다.

여기서 한 가지 디테일. **이벤트 record에 엔티티(`Order`)를 넘기지 않고 primitive(`orderId`, `totalAmount`, `finalAmount`)만 넘긴다.** 엔티티를 넘기면 두 도메인이 다시 엔티티 타입에 결합되고, JPA 영속성 컨텍스트를 가로질러 다니면서 detached entity 문제가 생긴다. primitive로 넘기고, 받는 쪽에서 필요하면 `EntityManager.getReference(Order.class, orderId)`로 프록시만 얻어 쓴다.

```java
// 실제 코드 — PaymentService.createReady()
public void createReady(Long orderId, int totalAmount, int finalAmount) {
    Order orderRef = em.getReference(Order.class, orderId);
    paymentRepository.save(Payment.create(
            orderRef, "", "주문 결제", finalAmount, totalAmount, finalAmount
    ));
}
```

`getReference()`는 실제 SELECT를 발생시키지 않는 프록시다. FK만 채우는 데는 이걸로 충분하고, **불필요한 SELECT 한 번을 아낀다.** 대용량 트래픽 환경에서는 이런 작은 절약이 누적되어 의미 있는 차이를 만든다.

### AFTER_COMMIT vs 동기 리스너 — 이벤트마다 시점이 다르다

이벤트 기반 구조의 두 번째 디테일은 **언제 리스너를 실행할 것인가**다. 모든 이벤트를 같은 시점에 처리하면 안 된다.

```java
// 실제 코드 — OrderEventListener
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailedAfterCommit(PaymentFailedEvent event) {
        orderPaymentTimeout.removeTimeout(event.orderId());
    }

    @EventListener
    public void onPaymentFullyCanceled(PaymentFullyCanceledEvent event) {
        orderService.cancelOrder(event.orderId());
    }
}
```

`@EventListener`(동기)와 `@TransactionalEventListener(AFTER_COMMIT)`(커밋 후 비동기)가 섞여있다. 무작위로 고른 게 아니다. 각 이벤트마다 **"트랜잭션이 롤백되면 이 작업도 취소되어야 하는가"** 를 따져서 정했다.

| 이벤트 | 리스너 시점 | 이유 |
|---|---|---|
| `OrderPlacedEvent` (Payment READY 생성) | 동기 (`@EventListener`) | Payment 저장이 실패하면 주문 자체도 롤백되어야 함 — 같은 트랜잭션 내 |
| `OrderPlacedEvent` (Redis 타임아웃 등록) | `AFTER_COMMIT` | Redis는 트랜잭션 롤백되어도 안 돌아옴. 주문이 실제로 커밋된 후에만 타이머 등록해야 유령 데이터 방지 |
| `PaymentApprovedEvent` (Redis 타임아웃 제거) | `AFTER_COMMIT` | 결제 승인이 롤백됐는데 Redis는 이미 제거되면 → 30분 후에도 자동 취소가 안 됨. 결제 커밋된 뒤에 제거 |
| `PaymentFailedEvent` (주문 취소) | 동기 (`@EventListener`) | 결제 실패와 주문 취소는 원자적으로 처리되어야 함 — 같은 트랜잭션에 묶기 |
| `PaymentFailedEvent` (Redis 타임아웃 제거) | `AFTER_COMMIT` | 위와 같은 이유 |

핵심 원칙은 **"DB 작업끼리는 한 트랜잭션에 묶고, 외부 시스템(Redis)은 커밋 후에 건드린다"** 이다. Redis는 트랜잭션을 모르기 때문에, DB가 롤백돼도 Redis 변경은 그대로 남는다. 커밋 전에 Redis를 건드렸다가 DB가 롤백되면 — DB에는 없는 주문의 타이머가 Redis에만 남아 있는 유령 상태가 된다. 이 작은 어긋남이 새벽 3시 장애 알람으로 돌아온다.

### confirmPayment 멱등성 — 비관적 락으로 동시 호출 차단

토스 콜백은 사용자 브라우저를 경유하기 때문에, **네트워크 재시도, 새로고침, 페이지 더블 클릭 등으로 같은 콜백이 여러 번 도착할 수 있다.** 같은 `paymentKey`로 confirm 요청이 두 번 들어오면? 첫 번째는 정상 승인, 두 번째는… 운이 나쁘면 **같은 결제를 두 번 승인**하려 시도한다.

토스 측은 자체 멱등성 보장이 있어서 두 번째 호출은 실패하지만, 우리 DB 입장에서는 두 스레드가 같은 Payment 엔티티를 동시에 조회하고 둘 다 "READY 상태네, 승인하자"라고 판단할 수 있다. 결과적으로 한 번은 정상 승인되고, 다른 한 번은 토스 API에서 거부당하면서 우리 DB 상태가 어긋난다.

해결은 **비관적 락으로 Payment를 조회**하는 것이다. 첫 번째 스레드가 락을 잡으면 두 번째는 대기한다. 첫 번째가 commit하면서 status를 DONE으로 바꾸면, 두 번째는 락 해제 후 그 변경된 status를 보게 된다.

```java
// 실제 코드 — PaymentRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Payment p WHERE p.order.pgOrderId = :pgOrderId")
Optional<Payment> findByOrderPgOrderIdWithLock(@Param("pgOrderId") String pgOrderId);
```

```java
// 실제 코드 — PaymentValidator.validateApprovable()
public void validateApprovable(Payment payment) {
    if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.IN_PROGRESS) {
        throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
    }
}
```

흐름은 이렇게 흘러간다.

```
[스레드 A] findByOrderPgOrderIdWithLock(pgOrderId) → 락 획득 → status=READY 확인 → 승인 → commit (status=DONE)
[스레드 B] findByOrderPgOrderIdWithLock(pgOrderId) → 대기...
                                                    ↓ A 커밋 후 락 해제
                                                  → 락 획득 → status=DONE 확인
                                                  → validateApprovable에서 PAYMENT_ALREADY_PROCESSED 거절
```

`PaymentIdempotencyTest.concurrentIdempotency` 테스트에서 같은 `pgOrderId`로 동시 confirm 요청을 보내 검증했고, **첫 번째만 성공하고 나머지는 모두 거절되는 것**을 확인했다. 비관적 락은 DB 커넥션을 잡고 있는 동안 다른 요청을 블록하는 비용이 있지만, 결제 confirm은 호출 빈도가 낮고(주문 생성 시 1회), 락 점유 시간이 짧아(토스 API 응답 ~500ms) 충분히 허용 가능하다고 판단했다.

### 시크릿 키와 외부 설정 — 코드와 분리한다

토스 API를 호출하려면 Secret Key가 필요하다. 이걸 코드에 박으면 GitHub에 그대로 올라가 누구나 우리 결제를 조작할 수 있다. 가장 흔한 보안 사고 1순위.

`@ConfigurationProperties`로 외부 설정 파일에 분리했다.

```java
// 실제 코드 — TossPaymentProperties
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

```properties
# 실제 코드 — application.properties
toss.payments.secret-key=test_sk_0RnYX2w532DgPq7bWZq1rNeyqApQ
toss.payments.confirm-url=https://api.tosspayments.com/v1/payments/confirm
toss.payments.cancel-url=https://api.tosspayments.com/v1/payments
```

지금은 테스트 키라 application.properties에 그대로 있지만, 운영 키는 환경 변수나 AWS Secrets Manager로 주입한다. **`@Value`로 한 줄씩 읽지 않고 `@ConfigurationProperties` 객체로 묶은 이유**는, 토스 관련 설정이 한 곳에 모여 있는 게 검색·테스트·교체에 유리하기 때문이다. URL이 바뀔 때 이 클래스 한 곳만 보면 된다.

토스 API 인증은 Secret Key를 Base64로 인코딩한 Basic Auth다.

```java
// 실제 코드 — TossPaymentClient
private HttpHeaders createAuthHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Authorization", "Basic " + encodeSecretKey());
    return headers;
}

private String encodeSecretKey() {
    return Base64.getEncoder().encodeToString(
            (tossProperties.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8)
    );
}
```

`secretKey + ":"` 패턴은 Basic Auth 표준이다. 사용자명 자리에 secretKey, 비밀번호 자리는 빈 문자열. 이 한 줄은 토스 공식 문서를 따른 것이지만, **표준 HTTP Basic Auth 형식이라 어떤 PG로 갈아타도 비슷한 패턴이 나올 가능성이 높다.** 그래서 `createAuthHeaders()`라는 이름으로 추출해뒀다 — "토스 인증 헤더"가 아니라 "인증 헤더 생성".

### 토스 API 호출 자체 — RestTemplate과 예외 매핑

토스 API 호출 자체는 의외로 단순하다.

```java
// 실제 코드 — TossPaymentClient.confirmPayment()
@Override
public void confirmPayment(String paymentKey, String orderId, Integer amount) {
    HttpHeaders headers = createAuthHeaders();

    Map<String, Object> body = Map.of(
            "paymentKey", paymentKey,
            "orderId", orderId,
            "amount", amount
    );

    try {
        restTemplate.postForEntity(
                tossProperties.getConfirmUrl(),
                new HttpEntity<>(body, headers),
                String.class
        );
        log.info("토스 결제 승인 성공: orderId={}", orderId);
    } catch (HttpClientErrorException e) {
        log.error("토스 결제 승인 실패: orderId={}, status={}, body={}", orderId, e.getStatusCode(), e.getResponseBodyAsString());
        throw new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED);
    } catch (RestClientException e) {
        log.error("토스 결제 승인 실패: orderId={}, error={}", orderId, e.getMessage());
        throw new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED);
    }
}
```

여기서 신경 쓴 디테일 두 가지.

**첫째, 예외를 두 단계로 잡는다.** `HttpClientErrorException`은 4xx 응답(요청 잘못됨), `RestClientException`은 그 외 통신 실패(네트워크, 타임아웃 등). 둘 다 `BusinessException(PAYMENT_APPROVAL_FAILED)`으로 변환하지만, **로그는 다르게 남긴다.** 4xx는 응답 body까지 찍고, 통신 실패는 메시지를 찍는다. 운영 중 장애가 났을 때 "이게 토스 거절인지 우리 네트워크 문제인지"를 로그만 보고 구분하기 위해서다.

**둘째, 외부 API 예외를 그대로 위로 던지지 않고 우리 도메인 예외로 변환한다.** `RestClientException`이 컨트롤러까지 올라가면 `GlobalExceptionHandler`가 어떤 응답 코드로 처리해야 할지 모른다. `BusinessException`으로 변환하면 우리가 정의한 ErrorCode 체계 안에서 일관되게 처리된다. **외부 시스템의 예외가 우리 도메인을 침범하지 않도록 경계에서 변환**하는 패턴이다.

### 외부 API를 트랜잭션 안에 둘 것인가

`confirmPayment()` 메서드는 `@Transactional`이 붙어 있고, 그 안에서 토스 API를 호출한다.

```java
@Transactional
public PaymentResponse confirmPayment(String paymentKey, String pgOrderId, Integer tossAmount) {
    // ... 락 조회, 검증
    paymentClientFactory.getClient("TOSS").confirmPayment(paymentKey, pgOrderId, tossAmount);  // 외부 API
    return approvePayment(payment, paymentKey, tossAmount);  // 우리 DB 반영
}
```

원래 외부 API 호출은 트랜잭션 안에서 피해야 하는 패턴이다. **응답 시간이 길어지면 그동안 DB 커넥션이 잡혀있고, 외부 API가 죽으면 우리 트랜잭션도 같이 죽는다.** 그런데 이 경우엔 의도적으로 안에 뒀다. 이유는:

토스 승인 API는 **"호출했지만 응답을 못 받은" 상태가 가장 위험하다.** 토스 측은 승인했는데 우리는 모르는 상태가 되면, 사용자는 결제를 했지만 우리 DB는 READY로 남아 30분 뒤 자동 취소된다 — 사용자 입장에서는 돈은 빠져나갔는데 주문은 취소되는 최악의 시나리오. 이걸 막으려면 **"토스 승인 ↔ 우리 DB 변경"이 원자적으로 묶여야** 한다.

만약 토스 호출을 트랜잭션 밖에서 하고 응답을 받은 뒤 별도 트랜잭션으로 DB를 업데이트한다면, 두 작업 사이에서 서버가 죽거나 예외가 나는 순간 상태가 어긋난다. 트랜잭션 안에서 토스를 호출하면 — 토스 API가 예외를 던져도, 우리 DB 변경이 실패해도, 모두 같이 롤백된다. 둘 중 하나라도 실패하면 우리 DB는 깔끔하게 READY로 남고, 사용자는 다시 시도할 수 있다.

이건 트레이드오프다. 응답 시간 동안 커넥션을 점유하지만, 결제 승인 빈도가 상대적으로 낮고 토스 응답이 보통 500ms 이내로 와서 허용 가능하다고 판단했다. 더 중요한 건 **데이터 정합성을 응답 시간보다 우선**한 것이다. 결제는 빨라야 하는 영역이 아니라 **틀리면 안 되는 영역**이다.

### 결제 실패 — failUrl과 자동 복구

사용자가 결제창에서 "취소"를 누르거나 인증에 실패하면, 토스는 우리 서버의 `failUrl`로 리다이렉트한다.

```java
// 실제 코드 — PaymentService.handlePaymentFailure()
@Transactional
public void handlePaymentFailure(String pgOrderId) {
    Payment payment = findPaymentByOrderId(pgOrderId);

    payment.abort();
    eventPublisher.publishEvent(new PaymentFailedEvent(payment.getOrder().getOrderId()));
}
```

Payment 상태를 ABORTED로 바꾸고 이벤트를 발행한다. 그 다음은 위에서 본 `OrderEventListener`가 받아서 `orderService.cancelOrder()`로 주문 취소 + 재고 복구를 처리한다(동기 리스너로 같은 트랜잭션에 묶여서). 그리고 `AFTER_COMMIT` 리스너가 Redis 타임아웃을 제거한다.

여기서 한 가지 짚을 점. **`PaymentService`는 직접 `OrderService.cancelOrder()`를 호출하지 않는다.** 이벤트만 발행한다. 위에서 도메인 분리를 위해 만든 구조가 여기서 그대로 쓰인다. **결제 실패라는 한 사건이 → 결제 도메인의 abort + 주문 도메인의 취소 + Redis 타이머 제거라는 세 가지 후속 작업으로 자연스럽게 분기**된다. 각 도메인은 자기 일만 한다.

이 시점에서 다시 한번 느낀 건, 도메인 분리는 "예쁜 코드를 위한 사치"가 아니라 **변경에 대한 보험**이라는 점이다. 만약 나중에 결제 실패 시 알림 발송이 추가되면? `NotificationEventListener`를 하나 더 만들어서 같은 `PaymentFailedEvent`를 구독하면 된다. `PaymentService`도 `OrderService`도 한 글자 안 바뀐다.

---

## 정리 — "실패 시나리오 카탈로그가 곧 설계 명세다"

이 결제 시스템의 모든 핵심 결정은 **"이게 실패하면 무엇이 무너지는가"** 라는 단일 질문에서 도출됐다. 각 시나리오에 대응하는 메커니즘 매핑:

| 실패 시나리오 | 차단 메커니즘 | 비용 |
|------------|-------------|------|
| 결제창에서 사용자가 수 분 멈춤 | 주문/결제 트랜잭션 분리 | pgOrderId + requestedAmount 영속화 |
| DB PK 외부 노출 | UUID 기반 pgOrderId | 컬럼 1개 추가 |
| amount 클라이언트 조작 | DB requestedAmount 비교 (SSOT) | 검증 로직 1개 |
| PG 전환 필요 | PaymentClient 인터페이스 + 팩토리 | 추상화 1단계 |
| 도메인 간 순환 의존 | 이벤트 기반 통신 | 이벤트 모델 |
| 트랜잭션 롤백 시 외부 호출 | `@TransactionalEventListener(AFTER_COMMIT)` | 이벤트 단방향 종속 |
| 콜백 중복 도착 | 비관적 락으로 멱등성 보장 | 락 점유 비용 |
| 토스 호출 중 서버 다운 | 토스 호출과 DB 변경을 같은 트랜잭션 | 트랜잭션 길이 증가 |
| 결제 미완료 시 재고 잠김 | Redis ZSET 타이머 + DB 보정 (이중 안전망) | Redis 인프라 + 배치 |
| 시크릿 키 노출 | `@ConfigurationProperties` 외부 분리 | 설정 1개 |

이 표가 의미하는 본질:

> **결제 시스템 설계는 "정상 경로를 잘 만드는 것"이 아니라 "실패 경로를 어디까지 차단할 것인지의 결정 모음"이다.** 모든 차단 메커니즘은 비용을 동반하고, 어떤 시나리오를 차단하고 어떤 시나리오를 (의식적으로) 허용할지 판단하는 것이 곧 설계의 핵심이다.

토스 공식 문서가 답해주는 영역은 **"API를 어떻게 호출하는가"** 까지다. 그 위에 **"실패가 일어났을 때 시스템이 어떻게 회복하는가"** 는 도메인을 이해하는 엔지니어만 답할 수 있다. 이 80%의 영역이 결제 시스템의 진짜 가치이고, 단순한 SDK 통합과 production-grade 시스템을 가르는 경계다.
