# 주문 → 결제 전체 흐름

## 전체 흐름 요약

```
[사용자] 주문하기 클릭
    ↓
[서버] POST /api/v1/orders — 주문 생성 (트랜잭션 1)
    ├── 주문 + 주문항목 저장
    ├── 비관적 락으로 재고 차감
    ├── 이벤트 발행 (OrderCreatedEvent)
    │     ├── [BEFORE_COMMIT] Payment READY 생성 (같은 트랜잭션)
    │     └── [AFTER_COMMIT] Redis ZSET 30분 타이머 등록 (트랜잭션 밖)
    └── 응답: pgOrderId, finalAmount 반환
    ↓
[사용자] 결제창에서 카드 선택, 인증 (수초 ~ 수분)
    ↓
[토스] 인증 완료 → successUrl로 리다이렉트
    ↓
[서버] GET /api/v1/payments/success — 결제 승인 (트랜잭션 2)
    ├── pgOrderId로 Payment 조회
    ├── 금액 위변조 검증 (DB requestedAmount vs 토스 amount)
    ├── 토스 승인 API 호출 (POST /v1/payments/confirm)
    ├── Payment 상태 DONE + paymentKey 저장
    ├── Order 상태 PAID
    └── Redis 타이머 제거
```

---

## 상세 흐름

### 1. 주문 생성 — POST /api/v1/orders

#### 요청

```
POST http://localhost:8080/api/v1/orders
Cookie: JSESSIONID={세션ID}
Content-Type: application/json

{
  "addressId": 1,
  "items": [
    { "productOptionId": 1, "quantity": 2 },
    { "productOptionId": 3, "quantity": 1 }
  ]
}
```

#### 서버 내부 처리 (트랜잭션 1)

```
placeOrder() [@Transactional]
│
├── 1. 배송지 + 사용자 조회
│     userService.findAddressByOwner(userId, addressId)
│
├── 2. 주문 생성
│     Order.create(user, address) → pgOrderId 생성 (UUID 기반)
│     orderRepository.save(order)
│
├── 3. 재고 차감 (decreaseStockAndCreateItems)
│     ├── 같은 옵션 수량 합산 (mergeAndSort)
│     ├── optionId 오름차순 정렬 → deadlock 방지
│     └── 각 옵션에 비관적 락(SELECT FOR UPDATE) → 재고 차감
│
├── 4. 이벤트 발행 (OrderCreatedEvent)
│     하나의 이벤트로 두 리스너가 동작:
│
│     [BEFORE_COMMIT] PaymentReadyEventListener (Payment 도메인)
│     └── Payment 엔티티를 READY 상태로 생성
│         requestedAmount = finalAmount (위변조 검증 기준)
│         paymentKey = "" (아직 토스 인증 전)
│
│     [AFTER_COMMIT] OrderCreatedEventListener (Order 도메인)
│     └── Redis ZSET에 30분 타임아웃 타이머 등록
│         score = 현재시각 + 30분
│
└── 5. 응답 반환
```

#### 응답

```json
{
  "orderId": 29,
  "pgOrderId": "ORD-20260402-a724ecc3",
  "orderStatus": "PENDING",
  "items": [
    {
      "orderItemId": 25,
      "productOptionId": 1,
      "productName": "나이키 에어맥스",
      "color": "BLACK",
      "size": "270",
      "quantity": 2,
      "unitPrice": 129000,
      "totalPrice": 258000
    }
  ],
  "totalAmount": 258000,
  "finalAmount": 258000
}
```

#### 트랜잭션 1 범위

```
@Transactional 시작
├── Order INSERT
├── OrderItem INSERT (N건)
├── ProductOption UPDATE (재고 차감, 비관적 락)
├── Payment INSERT (BEFORE_COMMIT 이벤트)
@Transactional 커밋
└── Redis ZADD (AFTER_COMMIT 이벤트, 트랜잭션 밖)
```

- DB 작업 (Order, OrderItem, ProductOption, Payment)은 하나의 트랜잭션으로 원자성 보장
- Redis 타이머 등록은 커밋 후 실행 → 롤백 시 Redis 오염 방지
- Payment 생성은 커밋 전 실행 → 주문과 같은 트랜잭션에서 원자성 보장

---

### 2. 사용자 결제 인증 — 토스 결제창

#### 프론트 (test-payment.html)

```
http://localhost:8080/test-payment.html
```

- pgOrderId와 finalAmount를 입력
- 결제하기 클릭 → 토스 JS SDK가 결제위젯 렌더링
- 사용자가 결제 수단 선택 → 인증 완료

#### 이 시간 동안 서버 상태

```
Order: PENDING (결제 대기)
Payment: READY (승인 전)
재고: 이미 차감됨
Redis: 30분 타이머 작동 중
DB 커넥션: 반환됨 (트랜잭션 1은 이미 끝남)
```

트랜잭션을 분리한 이유: 사용자가 결제창에서 고민하는 동안 DB 커넥션을 점유하면 대용량에서 커넥션 풀 고갈.

---

### 3. 결제 승인 — GET /api/v1/payments/success

#### 요청 (토스가 자동 리다이렉트)

```
GET http://localhost:8080/api/v1/payments/success
  ?paymentKey=tgen_20260402xxxxxx
  &orderId=ORD-20260402-a724ecc3
  &amount=258000
```

- paymentKey: 토스가 인증 완료 후 생성한 결제 고유키
- orderId: 우리가 생성한 pgOrderId
- amount: 결제 금액

#### 서버 내부 처리 (트랜잭션 2)

```
approvePayment() [@Transactional]
│
├── 1. Payment 조회
│     paymentRepository.findByOrderPgOrderId(orderId)
│     → pgOrderId로 트랜잭션 1에서 생성한 Payment를 찾는다
│
├── 2. 상태 검증 (PaymentValidator)
│     validateApprovable(payment)
│     → READY 또는 IN_PROGRESS 상태만 승인 가능
│
├── 3. 금액 위변조 검증 (PaymentValidator)
│     validateAmount(payment, amount)
│     → DB의 requestedAmount(258000) vs 토스의 amount(258000) 비교
│     → 불일치 시 PAYMENT_AMOUNT_MISMATCH 예외
│
├── 4. 토스 승인 API 호출
│     paymentClientFactory.getClient("TOSS").confirmPayment(paymentKey, orderId, amount)
│     → POST https://api.tosspayments.com/v1/payments/confirm
│     → Authorization: Basic {Base64(시크릿키:)}
│     → 200 OK 받으면 실제 결제 완료
│
├── 5. 상태 변경
│     payment.approve(paymentKey, amount) → DONE + paymentKey 저장
│     order.markPaid() → PAID
│
└── 6. Redis 타이머 제거
      orderPaymentTimeout.removeTimeout(orderId)
      → 결제 완료됐으니 타임아웃 취소
```

#### 응답

```json
{
  "paymentId": 1,
  "paymentKey": "tgen_20260402xxxxxx",
  "orderId": "ORD-20260402-a724ecc3",
  "status": "DONE",
  "totalAmount": 258000,
  "approvedAmount": 258000,
  "approvedAt": "2026-04-02T18:15:00"
}
```

#### 트랜잭션 2 범위

```
@Transactional 시작
├── Payment SELECT (pgOrderId로 조회)
├── 상태 검증 + 금액 검증
├── 토스 승인 API 호출 (외부 HTTP, 트랜잭션 안에서 호출)
├── Payment UPDATE (status=DONE, paymentKey 저장)
├── Order UPDATE (status=PAID)
@Transactional 커밋
└── Redis ZREM (타이머 제거, 트랜잭션 안에서 호출)
```

- 토스 API를 트랜잭션 안에 둔 이유: 실패 시 상태 변경이 자동 롤백
- 트레이드오프: 토스 응답 시간(~500ms) 동안 DB 커넥션 점유
- 결제 승인은 주문 생성보다 빈도가 낮아 허용 가능

---

### 4. 결제 실패 — GET /api/v1/payments/fail

#### 요청 (토스가 자동 리다이렉트)

```
GET http://localhost:8080/api/v1/payments/fail
  ?code=PAY_PROCESS_CANCELED
  &message=사용자가 결제를 취소했습니다
  &orderId=ORD-20260402-a724ecc3
```

#### 서버 내부 처리

```
handlePaymentFailure() [@Transactional]
│
├── Payment 조회 → abort() → ABORTED
├── orderService.cancelOrder(orderId)
│     ├── Order 비관적 락 → cancel() → CANCELLED
│     └── restoreStock() → 재고 복구 (optionId 오름차순 정렬)
└── Redis 타이머 제거
```

---

### 5. 30분 미결제 타임아웃

사용자가 결제를 안 하고 방치하면:

```
OrderTimeoutScheduler [@Scheduled(fixedDelay = 60000)]
│
├── Redis ZSET에서 만료된 주문 조회 (Lua 스크립트, 원자적 처리)
├── 각 주문에 대해:
│     └── orderService.cancelOrder(orderId) → 주문 취소 + 재고 복구
│
└── DB 보정 스케줄러 [@Scheduled(fixedDelay = 3600000)]
      Redis 장애 시 DB에서 30분 지난 PENDING 주문 직접 탐색
```

---

### 6. 결제 취소 — POST /api/v1/payments/{paymentId}/cancel

#### 요청

```
POST http://localhost:8080/api/v1/payments/1/cancel
Content-Type: application/json

{
  "cancelReason": "고객 요청",
  "cancelAmount": 50000        ← 없으면 전액 취소
}
```

#### 서버 내부 처리

```
cancelPayment() [@Transactional]
│
├── Payment 조회
├── cancelPolicy.validate(order) → 배송 상태별 취소 가능 여부 검증
│     ├── 배송 전: 취소 가능
│     ├── 배송 중: 취소 불가 (PM007)
│     └── 배송 완료 + 7일 초과: 환불 불가 (PM008)
├── paymentValidator.validateCancelable(payment) → 결제 상태 검증
├── paymentValidator.validateCancelAmount(payment, cancelAmount) → 잔액 초과 검증
├── 토스 취소 API 호출
│     POST /v1/payments/{paymentKey}/cancel
├── payment.cancel(reason, amount) → CANCELED 또는 PARTIAL_CANCELED
└── 전액 취소 시 order.cancelPaid() → CANCELLED
```

---

## 두 트랜잭션의 연결 고리

```
트랜잭션 1 (placeOrder)
│ 저장: pgOrderId = "ORD-20260402-a724ecc3"
│ 저장: requestedAmount = 258000
│
│ ... 사용자 결제 인증 (수초 ~ 수분, DB 커넥션 없음) ...
│
트랜잭션 2 (approvePayment)
  조회: pgOrderId로 Payment 찾기
  검증: requestedAmount(258000) == amount(258000) → 위변조 검증 통과
```

- pgOrderId: 두 트랜잭션을 잇는 식별자
- requestedAmount: 조작 불가능한 금액 기준 (DB에 저장, 클라이언트 접근 불가)

---

## 상태 전이도

### Order 상태

```
PENDING (주문 생성)
  ├── → PAID (결제 승인 완료)
  │       └── → CANCELLED (결제 취소)
  ├── → CANCELLED (결제 실패 / 타임아웃)
  ├── → SHIPPING (배송 시작)
  └── → DELIVERED (배송 완료)
```

### Payment 상태

```
READY (주문 생성 시)
  ├── → DONE (결제 승인 완료)
  │       ├── → CANCELED (전액 취소)
  │       └── → PARTIAL_CANCELED (부분 취소)
  │               └── → CANCELED (잔액 0)
  └── → ABORTED (결제 실패)
```

---

## 도메인 간 의존 방향

```
OrderService ──이벤트──→ PaymentReadyEventListener (Payment 도메인)
                         └── Payment READY 생성

PaymentService ──직접 호출──→ OrderService.cancelOrder()
                              └── 결제 실패 시 주문 취소 + 재고 복구
```

- Order → Payment: 이벤트로 분리 (OrderService가 Payment를 모름)
- Payment → Order: 직접 호출 (결제 실패 시 보상 트랜잭션)
