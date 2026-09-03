# 토스페이먼츠 결제 연동 — 보안·운영·안정성 세 축의 통합 설계 기록

## 왜 결제 연동을 "API 호출"이 아닌 "세 축의 동시 만족"으로 설계했나

결제 도메인의 본질은 단순하다 — **돈이 오가는 시스템**이다. 이 본질이 다른 도메인과 결정적으로 다른 점은, **"동작한다"가 곧 "안전하다"를 의미하지 않는다**는 사실이다. 정상 경로(API 호출)는 전체 작업의 약 20%일 뿐이고, 나머지 80%는 다음 세 축에 대한 답을 구성하는 코드다.

| 축 | 핵심 질문 | 채택 메커니즘 |
|----|---------|------------|
| **보안** | 클라이언트 경유 데이터를 어디까지 신뢰할 것인가 | DB requestedAmount 기반 위변조 검증 |
| **운영** | 사용자가 결제를 미완료하면 재고는 어떻게 회수할 것인가 | Redis ZSET 타이머 + DB 보정 스케줄러 (이중 안전망) |
| **안정성** | 외부 API 응답 시간 동안 DB 자원은 어떻게 보호할 것인가 | 주문/결제 트랜잭션 분리 + AFTER_COMMIT 이벤트 |

세 축은 각자 다른 메커니즘이 필요하며 단일 도구로 동시에 풀리지 않는다. 따라서 이 글은 토스페이먼츠 연동을 **API 통합 작업**이 아니라 **세 축이 동시에 만족되도록 메커니즘을 조합한 통합 설계 작업**으로 정리한다.

---

## 1. 전체 흐름

```
프론트(샌드박스) → 토스 결제창 → 사용자 인증
    → successUrl로 리다이렉트 (paymentKey, orderId, amount)
    → 우리 서버에서 금액 위변조 검증
    → 토스 승인 API 호출 (POST /v1/payments/confirm)
    → 결제 완료 (Payment → DONE, Order → PAID)
```

프론트가 없는 테스트 환경이므로 토스 샌드박스가 프론트 역할을 대신한다. 샌드박스에서 pgOrderId와 amount를 입력하면 토스가 결제창을 띄우고, 인증 완료 후 우리 서버의 successUrl로 리다이렉트한다.

---

## 2. 주문 생성 시 결제 금액을 서버에 저장

결제 흐름의 출발점은 주문 생성이다. 이때 두 가지를 반드시 해야 한다.

1. **pgOrderId 생성**: DB의 auto increment PK를 토스에 그대로 넘기면 주문 순서가 노출된다. UUID 기반으로 생성하여 외부에 PK를 노출하지 않는다.
2. **requestedAmount 저장**: 결제 요청 금액을 Payment 엔티티에 미리 저장한다. 이 값이 나중에 위변조 검증의 기준이 된다.

```java
@Transactional
public OrderResponse placeOrder(Long userId, OrderCreateRequest request) {
    Order savedOrder = orderRepository.save(Order.create(address.getUser(), address));
    List<OrderItem> savedItems = decreaseStockAndCreateItems(savedOrder, request.items());

    int totalAmount = savedItems.stream()
            .mapToInt(OrderItem::getTotalPrice)
            .sum();
    int finalAmount = savedOrder.calculateFinalAmount(totalAmount);

    // 위변조 검증을 위해 결제 요청 금액을 서버에 저장 (READY 상태)
    paymentRepository.save(Payment.create(
            savedOrder, "", "주문 결제",
            finalAmount, totalAmount, finalAmount, "TOSS"
    ));

    eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getOrderId()));

    return buildOrderResponse(savedOrder, savedItems);
}
```

Payment가 READY 상태로 DB에 저장되면 준비 완료다. 이제 프론트(샌드박스)에서 pgOrderId와 amount로 토스 결제창에 진입할 수 있다.

---

## 3. 금액 위변조 검증

### 왜 필요한가

토스 인증 완료 후 successUrl로 리다이렉트될 때, paymentKey/orderId/amount가 URL 파라미터로 전달된다.

```
GET /api/v1/payments/success?paymentKey=xxx&orderId=ORD-20260401-a1b2c3d4&amount=50000
```

문제는 이 리다이렉트가 **클라이언트 브라우저를 경유**한다는 것이다. 브라우저 개발자 도구로 amount를 조작할 수 있다.

### 공격 시나리오

```
1. 5만원짜리 상품을 주문한다
2. 결제 인증을 완료한다
3. 리다이렉트 URL의 amount=50000을 amount=1000으로 변경
4. 서버가 1000원으로 토스 승인 요청
5. 1000원만 결제되고 5만원짜리 상품을 받는다
```

### 해결: DB 금액과 비교

```java
@Transactional
public PaymentResponse approvePayment(String paymentKey, String orderId, Integer amount) {
    Payment payment = paymentRepository.findByOrderPgOrderId(orderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

    // 이미 처리된 결제인지 확인
    if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.IN_PROGRESS) {
        throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
    }

    // 금액 위변조 검증 — DB 저장 금액과 토스 전달 금액 비교
    if (!payment.getRequestedAmount().equals(amount)) {
        throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }

    // 검증 통과 → 토스 승인 API 호출
    paymentClientFactory.getClient(payment.getPgType())
            .confirmPayment(paymentKey, orderId, amount);

    payment.approve(amount);

    Order order = payment.getOrder();
    order.markPaid();

    orderPaymentTimeout.removeTimeout(order.getOrderId());

    return PaymentResponse.from(payment);
}
```

주문 생성 시 서버가 계산하여 DB에 저장한 `requestedAmount`와 토스에서 리다이렉트로 넘어온 `amount`를 비교한다. 클라이언트가 금액을 조작했다면 여기서 걸린다.

토스 공식문서에서도 "서버에서 반드시 검증하라"고 명시하고 있다. 토스는 우리 서버의 주문 금액을 모르기 때문에, 프론트에서 요청한 금액을 그대로 전달할 뿐이다.

---

## 4. 토스 승인 API 호출 — 인증 방식

금액 검증을 통과하면 토스에 최종 승인 요청을 보낸다.

```
POST https://api.tosspayments.com/v1/payments/confirm
```

토스는 HTTP Basic 인증을 사용한다. 시크릿키 뒤에 콜론을 붙이고 Base64로 인코딩한다.

```java
@Component
public class TossPaymentClient implements PaymentClient {

    @Override
    public void confirmPayment(String paymentKey, String orderId, Integer amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + encodeSecretKey());

        Map<String, Object> body = Map.of(
                "paymentKey", paymentKey,
                "orderId", orderId,
                "amount", amount
        );

        restTemplate.postForEntity(
                tossProperties.getConfirmUrl(),
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    private String encodeSecretKey() {
        return Base64.getEncoder().encodeToString(
                (tossProperties.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8)
        );
    }
}
```

토스에서 200 OK를 받으면 결제 완료다. Payment 상태를 DONE으로, Order 상태를 PAID로 변경한다.

---

## 4.5. 두 개의 트랜잭션을 어떻게 이었는가

주문 생성과 결제 승인은 별도 트랜잭션이다. 사이에 사용자의 결제 인증이 끼어있기 때문이다.

```
placeOrder()      [트랜잭션 1] → 주문 + 재고 차감 + Payment READY
                   ... 사용자가 결제창에서 인증 (수초 ~ 수분) ...
approvePayment()  [트랜잭션 2] → 위변조 검증 + 토스 승인 + PAID
```

이 둘을 잇는 연결 고리는 **pgOrderId**다.

1. `placeOrder()`에서 UUID 기반 pgOrderId를 생성하고, 같은 트랜잭션에서 Payment도 READY 상태로 저장한다. 이때 `requestedAmount`(결제 금액)를 기록해둔다.

2. 프론트(샌드박스)가 이 pgOrderId와 amount로 토스 결제창에 진입한다.

3. 토스 인증 완료 후 successUrl로 리다이렉트될 때 pgOrderId가 `orderId` 파라미터로 돌아온다.

4. `approvePayment()`에서 pgOrderId로 Payment를 조회하고, 1단계에서 저장한 `requestedAmount`와 토스가 보낸 `amount`를 비교한다.

```java
// 트랜잭션 1에서 저장
paymentRepository.save(Payment.create(savedOrder, "", "주문 결제", finalAmount, ...));

// 트랜잭션 2에서 조회 + 검증
Payment payment = paymentRepository.findByOrderPgOrderId(orderId);
if (!payment.getRequestedAmount().equals(amount)) { // 위변조 검증
    throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
}
```

DB에 저장된 금액이 두 트랜잭션 사이의 **신뢰 기준**이 된다. 클라이언트를 경유하는 amount는 조작 가능하지만, DB에 저장된 requestedAmount는 조작할 수 없다.

하나의 트랜잭션이었으면 이런 구조가 필요 없다. 하지만 사용자의 결제 인증 시간 동안 DB 커넥션을 점유하면 대용량에서 서비스가 죽는다. 트랜잭션을 분리하고, pgOrderId + requestedAmount로 연결하는 게 현실적인 해결책이다.

---

## 5. 결제 타임아웃 — 안 하면 재고가 묶인다

### 문제

```
1. 사용자가 주문한다 → 재고 10 → 9로 차감
2. 결제를 안 한다
3. 30분 동안 재고 1개가 묶여서 다른 사용자가 구매 불가
```

### 해결: Redis ZSET으로 30분 타이머

주문 생성 시 Redis ZSET에 만료 시각을 score로 등록한다. 스케줄러가 1분마다 만료된 주문을 찾아서 자동 취소 + 재고 복구한다.

```java
// 등록
public void registerTimeout(Long orderId) {
    double expireAt = System.currentTimeMillis() + TIMEOUT_MILLIS; // 현재 + 30분
    redisTemplate.opsForZSet().add("order:timeout", String.valueOf(orderId), expireAt);
}
```

### Lua 스크립트로 원자적 처리

다중 서버 환경에서 ZRANGEBYSCORE(조회)와 ZREM(삭제)을 분리하면, 서버 A와 서버 B가 같은 주문을 중복으로 취소할 수 있다. Lua 스크립트로 조회 + 삭제를 원자적으로 실행하여 이 문제를 방지한다.

```lua
local orders = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
if #orders > 0 then
    redis.call('ZREM', KEYS[1], unpack(orders))
end
return orders
```

Redis에서 Lua 스크립트는 실행 중 다른 명령이 끼어들 수 없다. "조회한 주문 = 삭제한 주문"이 보장된다.

### DB 보정 스케줄러 — Redis 장애 대비

Redis가 죽으면 타이머가 등록되지 못한 주문이 영원히 PENDING으로 남는다. 1시간마다 DB에서 30분 지난 PENDING 주문을 직접 탐색하는 보정 스케줄러를 별도로 둔다.

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

Redis 스케줄러가 1차 방어, DB 보정 스케줄러가 2차 방어. 이중 안전장치로 데이터 정합성을 보장한다.

---

## 6. 트랜잭션 경계 — 외부 호출을 트랜잭션 안에서 하면 안 되는 이유

### 문제 1: DB 커넥션 점유

`@Transactional` 안에서 Redis 등록이나 토스 API 호출을 하면, 외부 응답을 기다리는 동안 DB 커넥션을 물고 있다. HikariCP 기본 풀 크기가 10개인데, 동시 요청 100건이 들어오면 커넥션 풀이 고갈되어 전체 서비스가 멈춘다.

### 문제 2: 롤백 범위 불일치

트랜잭션 안에서 Redis에 데이터를 넣고, 이후 예외가 발생하면 DB는 롤백되지만 Redis는 롤백되지 않는다. 존재하지 않는 주문의 타이머가 Redis에 남는 정합성 문제가 생긴다.

### 해결: @TransactionalEventListener(AFTER_COMMIT)

```java
// 서비스 — 트랜잭션 안에서 이벤트만 발행
@Transactional
public OrderResponse placeOrder(...) {
    // DB 작업
    Order savedOrder = orderRepository.save(...);
    decreaseStockAndCreateItems(...);

    // 이벤트 발행 — 아직 실행 안 됨
    eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getOrderId()));

    return buildOrderResponse(...);
}
// 메서드 종료 → 트랜잭션 커밋 → 커넥션 반환 → 이벤트 리스너 실행

// 리스너 — 트랜잭션 커밋 후에만 실행
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleOrderCreated(OrderCreatedEvent event) {
    orderPaymentTimeout.registerTimeout(event.orderId());
}
```

AFTER_COMMIT으로 설정하면:
- 트랜잭션이 커밋된 후에만 Redis 등록이 실행된다
- 트랜잭션이 롤백되면 이벤트 리스너가 실행되지 않는다
- DB 커넥션은 이미 반환된 상태에서 외부 작업이 수행된다

---

## 7. 결제 실패 처리

토스 인증이 실패하면 failUrl로 리다이렉트된다.

```
GET /api/v1/payments/fail?code=PAY_PROCESS_CANCELED&message=사용자가 취소&orderId=ORD-xxx
```

이때 서버에서 해야 할 일:
1. Payment 상태를 ABORTED로 변경
2. 주문 취소 + 재고 복구 (cancelOrder 호출)
3. Redis 타임아웃 타이머 제거

```java
@Transactional
public void handlePaymentFailure(String orderId) {
    Payment payment = paymentRepository.findByOrderPgOrderId(orderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

    payment.abort();

    Order order = payment.getOrder();
    orderService.cancelOrder(order.getOrderId());
    orderPaymentTimeout.removeTimeout(order.getOrderId());
}
```

주문 취소 시 재고 복구에서도 optionId 오름차순으로 락을 획득하여 deadlock을 방지한다. 주문 생성(재고 차감)과 주문 취소(재고 복구)의 락 순서가 동일해야 deadlock이 발생하지 않는다.

---

## 8. 결제 취소/부분 취소/환불

### 토스 취소 API

승인된 결제를 취소하는 건 하나의 API로 처리된다.

```
POST https://api.tosspayments.com/v1/payments/{paymentKey}/cancel
```

cancelAmount를 넣지 않으면 전액 취소, 넣으면 부분 취소다. 환불도 같은 API를 사용하고 사유만 다르다.

```java
@Override
public void cancelPayment(String paymentKey, String cancelReason, Integer cancelAmount) {
    Map<String, Object> body = new HashMap<>();
    body.put("cancelReason", cancelReason);
    if (cancelAmount != null) {
        body.put("cancelAmount", cancelAmount);
    }

    String cancelUrl = "https://api.tosspayments.com/v1/payments/" + paymentKey + "/cancel";
    restTemplate.postForEntity(cancelUrl, new HttpEntity<>(body, headers), String.class);
}
```

### 부분 취소 시 잔액 관리

부분 취소는 여러 번 가능하다. 잔액이 0이 되면 전액 취소 상태로 자동 전환된다.

```java
public void cancelPartial(Integer amount, String reason) {
    this.cancelAmount = (this.cancelAmount != null ? this.cancelAmount : 0) + amount;
    this.balanceAmount = this.balanceAmount - amount;
    this.cancelReason = reason;
    this.status = (this.balanceAmount == 0) ? PaymentStatus.CANCELED : PaymentStatus.PARTIAL_CANCELED;
}
```

예시: 10만원 결제 건
1. 3만원 부분 취소 → 잔액 7만원, PARTIAL_CANCELED
2. 5만원 부분 취소 → 잔액 2만원, PARTIAL_CANCELED
3. 2만원 부분 취소 → 잔액 0원, CANCELED로 자동 전환

---

## 9. 환불 정책 — Policy 패턴으로 비즈니스 규칙 분리

### 문제

결제 취소가 무조건 가능하면 안 된다. 배송 중인 상품을 취소하거나, 배송 완료 후 한 달이 지난 주문을 환불하면 운영에 문제가 생긴다. 이런 비즈니스 규칙을 어디에 둘 것인가?

### 선택지

1. **엔티티에 검증 로직** — `order.validateCancelable()`. 간단하지만 규칙이 복잡해지면 엔티티가 비대해진다.
2. **서비스에 if-else** — 서비스 코드가 복잡해지고, 정책 변경 시 서비스를 수정해야 한다.
3. **Policy 클래스 분리** — 정책만 담당하는 독립 클래스. 변경 시 Policy만 수정.

### 해결: CancelPolicy

```java
@Component
public class CancelPolicy {

    private static final int REFUND_DAYS = 7;

    public void validate(Order order) {
        DeliveryStatus deliveryStatus = order.getDeliveryStatus();

        // 배송 중 — 취소 불가
        if (deliveryStatus == DeliveryStatus.SHIPPING) {
            throw new BusinessException(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
        }

        // 배송 완료 — 7일 이내만 환불 가능
        if (deliveryStatus == DeliveryStatus.DELIVERED) {
            LocalDateTime refundDeadline = order.getUpdatedAt().plusDays(REFUND_DAYS);
            if (LocalDateTime.now().isAfter(refundDeadline)) {
                throw new BusinessException(ErrorCode.REFUND_PERIOD_EXPIRED);
            }
        }
    }
}
```

서비스에서는 한 줄로 호출:

```java
cancelPolicy.validate(order);
```

### 배송 상태별 정리

| 배송 상태 | 취소 가능 | 이유 |
|---|---|---|
| null / PREPARING | O | 배송 전이라 무조건 취소 가능 |
| SHIPPING | X | 이미 발송되어 물류 비용 발생 |
| DELIVERED + 7일 이내 | O | 반품 후 환불 가능 기간 |
| DELIVERED + 7일 초과 | X | 환불 기간 만료 |

### 왜 Policy로 분리했는가

- 환불 기간이 7일 → 14일로 바뀌면 `REFUND_DAYS` 상수 하나만 수정
- VIP 고객은 30일 환불 같은 정책 추가 시 CancelPolicy를 상속/교체
- Order 엔티티는 상태 관리만, 비즈니스 규칙은 Policy가 담당 (SRP)
- 테스트 시 CancelPolicy만 Mock 가능

---

## 10. 트랜잭션 범위 — 매 작업마다 안/밖을 판단하다

결제 시스템에서 트랜잭션 범위는 "붙이면 끝"이 아니다. 모든 작업에 대해 **"이 작업이 실패하면 앞의 DB 변경도 롤백해야 하는가?"**를 기준으로 판단했다.

| 작업 | 트랜잭션 안/밖 | 근거 |
|---|---|---|
| 주문 저장 + 재고 차감 | 안 | 원자성 필수 — 둘 중 하나만 되면 안 됨 |
| Redis 타이머 등록 | 밖 (AFTER_COMMIT) | 롤백 시 Redis 오염 방지, 실패해도 주문은 유지 |
| 토스 승인 API 호출 | 안 | 실패 시 상태 변경 롤백 필요, 빈도 낮아 허용 |
| 토스 취소 API 호출 | 안 | 승인과 동일 판단 |
| 주문 목록/상세 조회 | readOnly | 더티체킹 스킵, 읽기 전용 커넥션 |
| 결제 실패 시 주문 취소 | 안 | Payment + Order + 재고가 원자적이어야 함 |

트레이드오프도 있다. 토스 API를 트랜잭션 안에서 호출하면 응답 시간 동안 커넥션을 점유한다. 하지만 결제 승인/취소는 주문 생성보다 빈도가 낮고, 토스 응답이 보통 500ms 이내라 허용 가능하다고 판단했다.

자세한 사례별 분석은 별도 글 ["트랜잭션 범위를 어디까지 잡아야 할까"](blog-트랜잭션범위고민.md)에서 다룬다.

---

## 전체 구조 요약

```
주문 생성 (placeOrder)
├── 주문 + 주문항목 저장
├── 비관적 락으로 재고 차감 (optionId 오름차순 → deadlock 방지)
├── Payment READY 상태로 저장 (requestedAmount 기록)
└── 트랜잭션 커밋 후 → Redis ZSET에 30분 타이머 등록

결제 성공 (approvePayment)
├── 금액 위변조 검증 (DB requestedAmount vs 토스 amount)
├── 토스 승인 API 호출 (POST /v1/payments/confirm)
├── Payment → DONE, Order → PAID
└── Redis 타이머 제거

결제 취소 (cancelPayment)
├── CancelPolicy로 배송 상태별 취소 가능 여부 검증
├── 부분 취소 시 잔액 초과 검증
├── 토스 취소 API 호출 (POST /v1/payments/{paymentKey}/cancel)
├── 전액 취소 → Payment CANCELED, Order CANCELLED
└── 부분 취소 → Payment PARTIAL_CANCELED (잔액 0이면 CANCELED)

결제 실패 (handlePaymentFailure)
├── Payment → ABORTED
├── 주문 취소 + 재고 복구
└── Redis 타이머 제거

타임아웃 (30분 미결제)
├── Redis 스케줄러 1분마다 폴링 (Lua 스크립트 원자적 처리)
├── DB 보정 스케줄러 1시간마다 (Redis 장애 대비)
└── 주문 취소 + 재고 복구
```

---

## 정리 — "API 호출 20% + 안전성 80%"라는 비율의 의미

토스페이먼츠 결제 연동에서 작업의 비중은 다음과 같이 분포한다.

| 축 | 비중 | 핵심 결정 |
|----|-----|---------|
| API 호출 (정상 경로) | ~20% | 전략 + 팩토리로 PG 추상화 |
| 보안 | ~25% | 클라이언트 경유 데이터 불신, DB SSOT 검증 |
| 운영 | ~25% | Redis ZSET + DB 보정 스케줄러 이중 안전망 |
| 안정성 | ~30% | 트랜잭션 분리 + AFTER_COMMIT + deadlock 락 순서 통일 |

이 비율이 의미하는 본질:

> **결제 시스템은 정상 경로의 우아함이 아니라 비정상 경로의 견고함으로 평가된다.** 클라이언트가 amount를 조작했을 때, 사용자가 결제창에서 사라졌을 때, Redis가 죽었을 때, 토스 API가 응답하지 않을 때 — 이 시나리오들에서 시스템이 어떻게 동작하는가가 결제 시스템의 진짜 품질이다.

세 축이 동시에 만족되어야만 production-grade 결제 시스템이 된다. 한 축이 빠지면 그 축에서 시한폭탄이 된다 — 보안 누락은 부정 결제, 운영 누락은 재고 잠김, 안정성 누락은 외부 의존이 시스템 전체로 전파되는 장애. 이 세 축을 의식적으로 분리해서 설계한 것이 이번 연동의 핵심 가치다.
