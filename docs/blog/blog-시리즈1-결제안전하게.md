# [시리즈 1] 결제 시스템을 안전하게 만들기 — 실패 시나리오 기반 설계

## 왜 결제 시스템을 "실패 시나리오 카탈로그"부터 시작했나

결제 도메인의 본질은 **돈이 오가는 시스템**이라는 한 문장으로 요약된다. 이 본질이 다른 도메인과 결정적으로 다른 점은, "동작한다"가 곧 "안전하다"를 의미하지 않는다는 사실이다. 정상 경로(happy path)에서 동작하는 코드는 결제 시스템 전체의 20%일 뿐이고, 나머지 80%는 다음 질문들에 답하는 코드다.

> "금액이 조작되면?", "사용자가 결제를 안 하면?", "토스 응답이 늦으면?", "Redis가 죽으면?", "다중 서버에서 같은 주문이 동시에 처리되면?", "트랜잭션이 롤백되면 외부 시스템은?"

이 글은 토스페이먼츠 결제 연동을 단순한 SDK 통합으로 보지 않고, **실패 시나리오 카탈로그를 먼저 작성한 뒤 각 시나리오를 차단하는 메커니즘을 설계한** 작업의 기록이다. 각 단계마다 "이게 실패하면 무엇이 무너지는가"를 명시화하고, 그 시나리오를 차단하는 비용이 정당한지 의식적으로 판단했다.

---

## 1. 결제 흐름 — 주문과 결제를 분리한 결정의 근거

토스페이먼츠 결제 흐름을 분석하면, **주문 생성과 결제 승인 사이에 사용자의 결제 인증 시간**이라는 비결정적 구간이 존재한다. 30초일 수도, 5분일 수도 있는 이 구간을 어떻게 다룰 것인가가 결제 도메인 설계의 첫 분기점이다.

```java
// 후보 A — 단일 트랜잭션
@Transactional
public OrderResponse placeOrder(...) {
    Order order = orderRepository.save(...);
    decreaseStock(...);
    tossPaymentClient.confirmPayment(...);  // 사용자 인증 포함
    return response;
}
```

후보 A는 코드 표면적으로 가장 간결하지만, **비결정적 구간을 트랜잭션 안에 포함**시킨다는 점에서 시스템 차원의 함정을 가진다.

```
사용자 결제 인증 시간 = DB 커넥션 점유 시간
→ HikariCP 풀 10개 환경에서 11번째 사용자부터 대기
→ 결제와 무관한 상품 조회·로그인까지 전부 정지
→ 결제 도메인의 외부 I/O가 시스템 전체 가용성을 결정
```

이 등식이 결정적이다. 후보 A는 "결제 도메인이 자기 비용을 도메인 안에 격리하지 않고 시스템 전체로 전파"시키는 구조다. 따라서 결제 도메인은 **트랜잭션을 둘로 분리**해야 한다는 결론이 도출된다.

```
placeOrder()      [트랜잭션 1] → 주문 생성 + 재고 차감 + Payment READY 저장
                   ... 사용자가 결제창에서 인증 (수초 ~ 수분, DB 커넥션 0개 점유) ...
approvePayment()  [트랜잭션 2] → 위변조 검증 + 토스 승인 + PAID
```

분리는 공짜가 아니다. 트랜잭션이 둘이 되는 순간 **정합성 보장 책임이 인프라에서 애플리케이션으로 이동**한다. 그 책임을 어떻게 설계했는지가 다음 절들의 주제다.

```
placeOrder()      [트랜잭션 1] → 주문 생성 + 재고 차감 + Payment READY 저장
                   ... 사용자가 결제창에서 인증 (수초 ~ 수분) ...
approvePayment()  [트랜잭션 2] → 위변조 검증 + 토스 승인 + PAID
```

---

## 2. 두 트랜잭션을 잇는 방법 — pgOrderId + requestedAmount

트랜잭션이 둘로 나뉘면 "둘 사이를 뭘로 연결하지?"가 문제다.

연결 고리는 **pgOrderId**와 **requestedAmount**다.

트랜잭션 1에서 UUID 기반 주문번호(pgOrderId)를 생성하고, 서버가 계산한 결제 금액(requestedAmount)을 Payment 엔티티에 저장한다. DB의 auto increment PK를 외부에 노출하지 않기 위해 UUID를 쓴다.

```java
// 트랜잭션 1 — placeOrder()
Order savedOrder = orderRepository.save(Order.create(address.getUser(), address));
List<OrderItem> savedItems = decreaseStockAndCreateItems(savedOrder, request.items());

int finalAmount = savedOrder.calculateFinalAmount(totalAmount);
paymentRepository.save(Payment.create(savedOrder, "", "주문 결제", finalAmount, ...));
```

프론트(샌드박스)가 이 pgOrderId와 amount로 토스 결제창에 진입한다. 사용자가 인증을 완료하면 토스가 successUrl로 리다이렉트하면서 pgOrderId를 돌려보낸다.

```
GET /api/v1/payments/success?paymentKey={토스키}&orderId={pgOrderId}&amount={금액}
```

트랜잭션 2에서 pgOrderId로 Payment를 조회하고, DB에 저장된 금액과 토스가 보낸 금액을 비교한다.

```java
// 트랜잭션 2 — approvePayment()
Payment payment = findPaymentByOrderId(orderId);
paymentValidator.validateAmount(payment, amount);  // DB 금액 vs 토스 금액
```

DB에 저장된 `requestedAmount`가 두 트랜잭션 사이의 **조작 불가능한 신뢰 기준(SSOT)** 이 된다.

이 패턴의 일반 원리: **분산된 트랜잭션 사이의 정합성은 서버가 직접 쓴 불변 기록으로만 이을 수 있다.** 클라이언트나 외부 시스템에서 들어오는 값은 검증 대상이지 신뢰 대상이 아니다. 이 원칙은 다음 절(위변조 검증)에서 그대로 활용된다.

---

## 3. 금액 위변조 — 클라이언트 경유 데이터를 신뢰하지 않는다는 원칙

successUrl로 리다이렉트될 때 amount는 **클라이언트 브라우저를 경유**한다. 개발자 도구로 URL의 amount를 바꿀 수 있다.

### 공격 시나리오

```
1. 5만원짜리 상품을 주문한다
2. 결제 인증을 완료한다
3. 리다이렉트 URL의 amount=50000을 amount=1000으로 변경
4. 서버가 1000원으로 토스 승인 요청
5. 1000원만 결제되고 5만원짜리 상품을 받는다
```

### 검증 코드

```java
@Component
public class PaymentValidator {

    public void validateAmount(Payment payment, Integer amount) {
        if (!payment.getRequestedAmount().equals(amount)) {
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }
}
```

트랜잭션 1에서 서버가 계산하여 DB에 저장한 `requestedAmount`와 비교한다. 클라이언트가 금액을 조작했다면 여기서 차단된다. 토스 공식문서에서도 "서버에서 반드시 검증하라"고 명시하고 있다 — 이 검증을 빼먹는 것은 결제 시스템의 보안 게이트를 비워두는 것과 같다.

검증 로직을 `PaymentValidator`로 별도 컴포넌트로 분리한 이유는 두 가지다.

1. **단일 책임 원칙** — 서비스 메서드의 비대화를 방지. 검증과 비즈니스 로직이 같은 메서드 안에 섞이면 어느 쪽이 변경되어도 다른 쪽의 회귀 테스트가 필요해진다
2. **검증 규칙의 진화 가능성** — 향후 위변조 검증이 추가될 때(IP 블랙리스트, 결제 한도, 사용자 검증 등) 한 컴포넌트에 모이면 보안 정책의 일관성이 유지된다

---

## 4. 결제를 안 하면 — 재고가 영원히 묶인다

```
1. 사용자가 주문한다 → 재고 10 → 9로 차감
2. 결제를 안 한다
3. 30분 동안 재고 1개가 묶여서 다른 사용자가 구매 불가
```

### Redis ZSET으로 30분 타이머

주문 생성 시 Redis ZSET에 만료 시각을 score로 등록한다. 스케줄러가 1분마다 만료된 주문을 찾아서 자동 취소 + 재고 복구한다.

```java
public void registerTimeout(Long orderId) {
    double expireAt = System.currentTimeMillis() + TIMEOUT_MILLIS;
    redisTemplate.opsForZSet().add("order:timeout", String.valueOf(orderId), expireAt);
}
```

### 타이머 등록은 트랜잭션 커밋 후에

타이머 등록을 트랜잭션 안에서 하면 두 가지 문제가 생긴다.

1. 트랜잭션이 롤백되어도 Redis에 타이머가 남는다 → 존재하지 않는 주문의 타이머
2. Redis 등록이 실패하면 주문까지 롤백된다 → Redis 때문에 주문이 안 됨

`@TransactionalEventListener(AFTER_COMMIT)`으로 해결했다.

```java
// 서비스 — 트랜잭션 안에서 이벤트만 발행
eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getOrderId()));

// 리스너 — 트랜잭션 커밋 후에만 실행
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleOrderCreated(OrderCreatedEvent event) {
    orderPaymentTimeout.registerTimeout(event.orderId());
}
```

커밋된 후에만 Redis에 등록되고, 롤백되면 이벤트 자체가 발행되지 않는다.

### 다중 서버에서 중복 취소 방지 — Lua 스크립트

서버가 여러 대면 같은 만료 주문을 동시에 가져갈 수 있다.

```
서버 A: ZRANGEBYSCORE → [주문1, 주문2]
서버 B: ZRANGEBYSCORE → [주문1, 주문2]  ← 같은 주문을 중복 조회
서버 A: cancelOrder(주문1)
서버 B: cancelOrder(주문1)  ← 이미 취소된 주문을 다시 취소
```

Lua 스크립트로 조회 + 삭제를 원자적으로 실행한다.

```lua
local orders = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
if #orders > 0 then
    redis.call('ZREM', KEYS[1], unpack(orders))
end
return orders
```

Redis에서 Lua 스크립트 실행 중에는 다른 명령이 끼어들 수 없다. "조회한 주문 = 삭제한 주문"이 보장된다.

### Redis가 죽으면 — DB 보정 스케줄러

Redis에 타이머가 등록되지 못한 주문이 있을 수 있다. 서버 크래시, Redis 메모리 부족 등. 이 주문은 영원히 PENDING으로 남는다.

1시간마다 DB에서 30분 지난 PENDING 주문을 직접 탐색하는 보정 스케줄러를 둔다.

```java
@Scheduled(fixedDelay = 3600000)
public void compensateOrphanedOrders() {
    LocalDateTime expiredTime = LocalDateTime.now().minusMinutes(30);
    List<Order> orphanedOrders = orderRepository.findExpiredOrders(
            OrderStatus.PENDING, expiredTime, PageRequest.of(0, BATCH_SIZE));

    for (Order order : orphanedOrders) {
        orderService.cancelOrder(order.getOrderId());
    }
}
```

Redis 스케줄러가 1차 방어, DB 보정 스케줄러가 2차 방어. 이중 안전장치다.

---

## 5. 트랜잭션 범위 — 매 작업마다 판단하다

모든 작업에 대해 **"이 작업이 실패하면 앞의 DB 변경도 롤백해야 하는가?"**를 기준으로 판단했다.

| 작업 | 트랜잭션 안/밖 | 근거 |
|---|---|---|
| 주문 저장 + 재고 차감 | 안 | 원자성 필수 |
| Redis 타이머 등록 | 밖 (AFTER_COMMIT) | 롤백 시 Redis 오염 방지 |
| 토스 승인 API 호출 | 안 | 실패 시 상태 변경 롤백 필요 |
| 토스 취소 API 호출 | 안 | 승인과 동일 판단 |
| 주문 조회 | readOnly | 더티체킹 스킵, 읽기 전용 커넥션 |

토스 API를 트랜잭션 안에 둔 건 트레이드오프다. 응답 시간 동안 커넥션을 점유하지만, 결제 승인 빈도가 낮고 토스 응답이 보통 500ms 이내라 허용 가능하다고 판단했다.

---

## 6. 결제 취소/부분 취소 — 환불 정책까지

### 토스 취소 API

승인된 결제의 전액 취소, 부분 취소, 환불 모두 하나의 API로 처리된다.

```
POST /v1/payments/{paymentKey}/cancel
```

cancelAmount를 넣지 않으면 전액 취소, 넣으면 부분 취소다. 부분 취소는 여러 번 가능하고, 잔액이 0이 되면 자동으로 전액 취소 상태로 전환된다.

```java
public void cancel(String reason, Integer cancelAmount) {
    if (cancelAmount == null) {
        this.status = PaymentStatus.CANCELED;
        this.cancelAmount = this.approvedAmount;
        this.balanceAmount = 0;
    } else {
        this.cancelAmount = (this.cancelAmount != null ? this.cancelAmount : 0) + cancelAmount;
        this.balanceAmount = this.balanceAmount - cancelAmount;
        this.status = (this.balanceAmount == 0) ? PaymentStatus.CANCELED : PaymentStatus.PARTIAL_CANCELED;
    }
}
```

### CancelPolicy — 정책 소유권의 의도적 결정

결제 취소가 무조건 가능하면 안 된다. 배송 중인 상품을 취소하거나, 한 달 전 주문을 환불하면 운영에 문제가 생긴다. 이 정책(CancelPolicy)을 어느 도메인에 둘 것인가가 설계의 결정 지점이다.

| 기준 | Order에 두기 | Payment에 두기 |
|------|------------|--------------|
| 데이터 접근 편의성 | ★★★ (배송 상태가 Order의 필드) | ★★ |
| 호출 시점의 의미 | "주문이 자기 상태를 검증" | "결제 취소가 가능한지 결제가 판단" |
| 변경 압력의 원천 | 배송 정책 변경 시 | **환불 정책 변경 시** |
| 호출자 | PaymentService | PaymentService |

**결정적 근거**: 환불 기간이 7일에서 14일로 바뀌는 변경이 발생할 때, 수정 압력의 원천은 **결제 도메인의 비즈니스 규칙**에 있다. 만약 이 정책이 Order에 있다면 결제 정책 변경이 주문 도메인의 코드를 수정시키는 **역방향 결합**이 생긴다.

원칙: **데이터는 읽기 전용으로 공유하되, 판단의 주체는 변경 압력을 받는 도메인에 둔다.** Order의 상태를 읽는 것은 허용하지만, 그 상태에 대한 해석 규칙은 Payment가 소유한다.

```java
@Component
public class CancelPolicy {

    private static final int REFUND_DAYS = 7;

    public void validate(Order order) {
        if (order.getDeliveryStatus() == DeliveryStatus.SHIPPING) {
            throw new BusinessException(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
        }

        if (order.getDeliveryStatus() == DeliveryStatus.DELIVERED) {
            LocalDateTime refundDeadline = order.getUpdatedAt().plusDays(REFUND_DAYS);
            if (LocalDateTime.now().isAfter(refundDeadline)) {
                throw new BusinessException(ErrorCode.REFUND_PERIOD_EXPIRED);
            }
        }
    }
}
```

환불 기간이 7일에서 14일로 바뀌면 `REFUND_DAYS` 상수 하나만 수정한다. VIP 고객에게 30일 환불을 허용하려면 CancelPolicy를 상속/교체한다. Order 엔티티는 건드리지 않는다.

---

## 7. 결제 실패 — 자동 복구

토스 인증이 실패하면 failUrl로 리다이렉트된다.

```java
public void handlePaymentFailure(String orderId) {
    Payment payment = findPaymentByOrderId(orderId);
    payment.abort();

    Order order = payment.getOrder();
    orderService.cancelOrder(order.getOrderId());
    orderPaymentTimeout.removeTimeout(order.getOrderId());
}
```

Payment → ABORTED, 주문 취소 + 재고 복구, 타이머 제거까지 한 트랜잭션에서 처리한다. 재고 복구 시에도 optionId 오름차순으로 락을 획득하여 deadlock을 방지한다. 주문 생성(재고 차감)과 같은 순서를 유지하는 게 핵심이다.

---

## 전체 흐름 한눈에

```
주문 생성 (placeOrder) [트랜잭션 1]
├── 주문 + 주문항목 저장
├── 비관적 락으로 재고 차감 (optionId 오름차순 → deadlock 방지)
├── Payment READY 저장 (requestedAmount 기록 → 위변조 검증 기준)
└── 트랜잭션 커밋 후 → Redis ZSET에 30분 타이머 등록

결제 승인 (approvePayment) [트랜잭션 2]
├── pgOrderId로 Payment 조회 → 두 트랜잭션의 연결 고리
├── 금액 위변조 검증 (DB requestedAmount vs 토스 amount)
├── 토스 승인 API 호출
├── Payment → DONE, Order → PAID
└── Redis 타이머 제거

결제 취소 (cancelPayment)
├── CancelPolicy로 배송 상태별 취소 가능 여부 검증
├── PaymentValidator로 상태/잔액 검증
├── 토스 취소 API 호출
└── 전액 취소 → CANCELED / 부분 취소 → PARTIAL_CANCELED

결제 실패 (handlePaymentFailure)
├── Payment → ABORTED
├── 주문 취소 + 재고 복구 (락 순서 유지)
└── Redis 타이머 제거

타임아웃 (30분 미결제)
├── Redis 스케줄러 1분마다 폴링 (Lua 스크립트 원자적 처리)
├── DB 보정 스케줄러 1시간마다 (Redis 장애 대비)
└── 주문 취소 + 재고 복구
```

---

## 정리 — "실패 시나리오 카탈로그가 곧 설계 명세다"

이 결제 시스템의 모든 핵심 결정은 **실패 시나리오를 먼저 명시화한 뒤 그 시나리오를 차단하는 메커니즘을 도입**하는 방식으로 도출됐다.

| 실패 시나리오 | 차단 메커니즘 | 비용 |
|-------------|-------------|------|
| 금액이 클라이언트에서 조작되면 | DB requestedAmount와 비교(SSOT 검증) | 영속화 컬럼 1개 + 검증 1줄 |
| 사용자가 결제를 안 하면 | Redis ZSET 30분 타이머 | Redis 인프라 + 스케줄러 |
| Redis가 죽으면 | DB 보정 스케줄러(이중 안전장치) | 1시간 주기 배치 |
| 다중 서버에서 중복 취소되면 | Lua 스크립트 원자적 처리 | Lua 1개 |
| DB 롤백 시 Redis만 남으면 | `@TransactionalEventListener(AFTER_COMMIT)` | 이벤트 1개 |
| 배송 중 취소 요청이 오면 | CancelPolicy로 도메인 정책 검증 | Policy 컴포넌트 1개 |

이 표가 의미하는 본질은 다음과 같다.

> **결제 시스템 설계는 "정상 경로를 잘 만드는 것"이 아니라 "실패 경로를 어디까지 차단할 것인지의 결정 모음"이다.** 모든 차단 메커니즘은 비용을 동반하고, 어떤 시나리오는 차단하고 어떤 시나리오는 (의식적으로) 허용할지 판단하는 것이 곧 설계의 핵심이다.

이번 작업에서 의식적으로 **차단하지 않은** 시나리오도 있다. 예를 들어 토스 승인 성공 후 우리 DB 저장 실패는 통계적으로 매우 드문 케이스이며, 발생 시 운영 알람 → 수동 보상 처리로 대응하는 것이 자동 보상 로직 도입 비용보다 유리하다고 판단했다. 이런 의식적 미차단도 설계의 일부다 — **모든 시나리오를 차단하려는 시도가 오히려 시스템을 더 취약하게 만들 수 있다.**
