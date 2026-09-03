# 주문과 결제 도메인을 분리하면서 고민한 것들

## 들어가며

이커머스에서 주문과 결제는 밀접하지만 별개의 도메인이다. 하나의 서비스에 몰아넣으면 간단하지만, 도메인이 커질수록 유지보수가 어려워진다. DDD 관점에서 두 도메인을 분리하면서 의존 방향, 트랜잭션 경계, 정책의 소속, 순환 의존 문제를 고민한 과정을 정리한다.

---

## 1. 의존 방향을 의식적으로 결정하다

### 현재 구조

```
OrderService → PaymentRepository (READY 생성만)
PaymentService → OrderService (cancelOrder 호출)
```

Order가 Payment를 완전히 모르는 건 아니다. 주문 생성 시 `PaymentRepository.save()`로 Payment를 READY 상태로 생성한다. 하지만 이건 **"결제 준비 데이터를 만든다"는 최소한의 의존**이다. Payment의 승인, 취소, 부분 취소 같은 비즈니스 로직은 전혀 모른다.

반대로 PaymentService는 OrderService의 `cancelOrder()`를 호출한다. 결제 실패 시 주문을 취소하고 재고를 복구해야 하기 때문이다. 이건 **보상 트랜잭션**이라 Payment → Order 방향의 의존이 불가피하다.

### 왜 이 방향인가

주문이 결제를 아는 것보다 **결제가 주문을 아는 게 자연스럽다.** 현실에서도 "주문이 결제를 처리한다"가 아니라 "결제가 실패하면 주문을 취소한다"이기 때문이다. 도메인 모델이 현실의 관계를 반영한다.

---

## 2. 트랜잭션 경계를 도메인 경계에 맞추다

### 주문과 결제는 별도 트랜잭션

```
placeOrder()      → 트랜잭션 1: 주문 생성 + 재고 차감 + Payment READY
                     (사용자가 결제창에서 인증 — 수초 ~ 수분)
approvePayment()  → 트랜잭션 2: 위변조 검증 + 토스 승인 + Payment DONE + Order PAID
```

하나의 트랜잭션으로 묶으면 사용자가 결제창에서 고민하는 동안 DB 커넥션을 점유한다. 대용량 트래픽에서 커넥션 풀이 즉시 고갈된다.

### 트랜잭션이 분리되면 정합성은?

두 트랜잭션 사이에 실패가 발생할 수 있다. 이를 **최종적 정합성(Eventual Consistency)**으로 해결한다.

| 실패 시나리오 | 대응 |
|---|---|
| 주문 생성 후 결제 안 함 | Redis ZSET 30분 타임아웃 → 자동 취소 + 재고 복구 |
| 결제 인증 실패 | failUrl → handlePaymentFailure() → 주문 취소 |
| Redis 타이머 누락 | DB 보정 스케줄러 1시간마다 PENDING 주문 탐색 |
| 토스 승인 성공 + DB 실패 | 토스 취소 API로 보상 처리 (TODO) |

"트랜잭션을 분리하면 정합성이 깨지지 않나요?"라는 질문에 이 표 하나로 답할 수 있다.

### 이벤트로 도메인 간 연결

```java
// OrderService — 주문 도메인에서 이벤트 발행
eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getOrderId()));

// OrderCreatedEventListener — 트랜잭션 커밋 후 Redis 타이머 등록
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleOrderCreated(OrderCreatedEvent event) {
    orderPaymentTimeout.registerTimeout(event.orderId());
}
```

주문 도메인은 이벤트를 발행할 뿐, 누가 듣는지 모른다. 도메인 간 결합도를 낮추면서 트랜잭션 커밋 후에만 부가 작업이 실행되도록 보장한다.

---

## 3. 정책은 어느 도메인에 속하는가

### 고민: CancelPolicy의 소속

환불 정책은 **배송 상태**를 검증한다. 배송 상태는 Order 엔티티의 필드다.

```java
@Component
public class CancelPolicy {

    public void validate(Order order) {
        if (order.getDeliveryStatus() == DeliveryStatus.SHIPPING) {
            throw new BusinessException(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
        }
        // ...
    }
}
```

Order의 데이터를 검증하니까 Order 도메인에 둬야 할까? 아니면 결제 취소 시 호출되니까 Payment 도메인에 둬야 할까?

### 결정: Payment 도메인

CancelPolicy는 **"결제를 취소해도 되는가?"**를 판단한다. 이 판단이 필요한 시점은 `PaymentService.cancelPayment()`이다. 호출하는 쪽의 도메인에 두는 게 응집도가 높다.

Order 도메인에 두면 Payment가 Order의 정책 클래스를 알아야 하는데, 이러면 "결제가 주문의 내부 규칙에 의존한다"는 어색한 구조가 된다. Payment 도메인에서 Order의 상태를 **읽기만** 하는 건 허용하되, 정책 판단의 주체는 Payment에 두었다.

---

## 4. 순환 의존을 피한 설계

### 문제

주문 생성 시 Payment를 READY 상태로 만들어야 한다. 자연스러운 방법은:

```java
// OrderService
paymentService.createReadyPayment(savedOrder, finalAmount);
```

하지만 PaymentService는 이미 OrderService를 의존하고 있다 (결제 실패 시 cancelOrder 호출).

```
OrderService → PaymentService → OrderService  ← 순환 의존!
```

Spring에서 순환 의존은 Bean 생성 실패를 유발한다.

### 해결: Repository 직접 사용

```java
// OrderService — PaymentService를 거치지 않고 Repository 직접 사용
paymentRepository.save(Payment.create(
        savedOrder, "", "주문 결제",
        finalAmount, totalAmount, finalAmount
));
```

Payment READY 생성은 **단순 INSERT**라 비즈니스 로직이 없다. 이런 경우 Service를 거치지 않고 Repository를 직접 사용하여 순환을 피하는 게 현실적이다.

### 대안: 이벤트로 완전 분리

```java
// OrderService
eventPublisher.publishEvent(new OrderCreatedEvent(orderId, finalAmount));

// PaymentEventListener (Payment 도메인)
@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
public void handleOrderCreated(OrderCreatedEvent event) {
    paymentRepository.save(Payment.create(...));
}
```

이벤트로 하면 OrderService가 PaymentRepository도 모르게 되어 완전한 분리가 가능하다. 하지만 단순 INSERT 하나에 이벤트까지 두는 건 과도하다고 판단하여 현재는 Repository 직접 사용 방식을 택했다.

---

## 5. 에러코드도 도메인별로 분리

```java
// Order 도메인
ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "OR001", ...),
INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "OR002", ...),

// Payment 도메인
PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PM001", ...),
PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PM002", ...),
```

에러코드 접두사(OR, PM)만 봐도 어느 도메인에서 발생한 에러인지 알 수 있다. 운영 환경에서 로그를 분석할 때 도메인별 필터링이 가능하다.

---

## 현재 도메인 경계 정리

```
Order 도메인
├── OrderController — 주문 생성/조회 API
├── OrderService — 주문 생성, 취소, 조회
├── Order, OrderItem — 엔티티
├── OrderCreatedEvent — 도메인 이벤트
└── OrderPaymentTimeout — Redis 타임아웃 관리

Payment 도메인
├── PaymentController — 결제 승인/취소/실패 API
├── PaymentService — 결제 승인, 취소, 실패 처리
├── Payment — 엔티티
├── PaymentClient (interface) — PG사 API 추상화
├── TossPaymentClient — 토스 구현체
├── PaymentClientFactory — PG사 선택 팩토리
├── PaymentValidator — 결제 상태/금액 검증
└── CancelPolicy — 환불 정책 (배송 상태, 환불 기간)
```

---

## 정리

- 의존 방향은 현실의 관계를 반영한다. 결제가 주문을 아는 게 자연스럽다.
- 트랜잭션 경계를 도메인 경계에 맞추고, 이벤트와 타임아웃으로 최종적 정합성을 보장한다.
- 정책 클래스는 호출하는 쪽의 도메인에 두어 응집도를 높인다.
- 순환 의존은 Repository 직접 사용으로 실용적으로 해결한다.
- "분리했다"가 중요한 게 아니라 "왜 이 경계로 나눴는가"를 설명할 수 있는 게 중요하다.
