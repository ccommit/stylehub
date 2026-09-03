# 트랜잭션 범위 설계 — 결제 시스템에서의 6가지 의사결정

## 왜 트랜잭션 범위를 첫 설계 단계에서 결정했나

`@Transactional`은 한 줄짜리 어노테이션이지만, 그 한 줄이 결정하는 것은 **DB 커넥션 점유 시간 = 시스템의 동시성 한계** 다. 결제 시스템처럼 외부 API, Redis, 다중 DB 작업이 한 흐름 안에 섞이는 도메인에서는, 트랜잭션 경계를 잘못 잡는 순간 다음 등식이 그대로 장애로 이어진다.

```
트랜잭션 범위 ⊇ 외부 I/O 응답 시간
→ DB 커넥션 점유 시간 = 외부 시스템 응답 시간
→ 동시 처리 가능 요청 수 ≈ HikariCP 풀 크기 / 외부 응답 시간
```

이 등식을 의식하지 않고 코드를 쓰면 단일 결제 API의 외부 호출이 상품 조회·로그인까지 멈추게 만든다. 그래서 결제 도메인을 구현하기 전에 **모든 작업에 대해 "트랜잭션 안인가 밖인가"를 결정하는 단일 판단 기준**을 먼저 세웠다. 이 글은 그 기준과, 실제 6가지 작업에 대한 의사결정 기록이다.

---

## 1. 트랜잭션이 길면 생기는 일

```java
@Transactional
public OrderResponse placeOrder(...) {
    Order order = orderRepository.save(...);        // DB 작업 (10ms)
    decreaseStock(...);                             // DB 작업 (20ms)
    tossPaymentClient.confirmPayment(...);          // 외부 API (2000ms)
    redisTemplate.opsForZSet().add(...);            // Redis 작업 (5ms)
    return response;
}
```

이 메서드의 트랜잭션은 약 2035ms 동안 유지된다. 그 동안 DB 커넥션 1개를 점유한다.

HikariCP 기본 커넥션 풀 크기는 10개다.

```
동시 주문 요청 100건
= 10개 커넥션으로 100건 처리
= 90건은 커넥션 대기
= 각 트랜잭션이 2초씩 점유
= 마지막 요청은 최대 20초 대기
= ConnectionTimeoutException → 서비스 장애
```

외부 API 응답이 느려지면 DB와 관계없는 작업 때문에 상품 조회, 로그인 등 모든 API가 멈춘다.

---

## 2. 단일 판단 기준 — 두 축의 동시 검증

설계 단계에서 채택한 판단 기준은 단일 질문이 아니라 **두 축의 동시 검증**이다.

```
축 1 (정합성): 이 작업이 실패하면 앞의 DB 변경도 롤백해야 하는가?
축 2 (자원 점유): 이 작업이 DB 커넥션을 점유한 채 진행될 가치가 있는가?
```

| 축 1 | 축 2 | 결론 |
|------|------|------|
| Yes | Yes | 트랜잭션 안 |
| Yes | No  | 트랜잭션 안 + 보상 로직 준비 |
| No  | Yes | 분리 가능 (트랜잭션 안에 둘지 선택) |
| No  | No  | 트랜잭션 밖 (이벤트 AFTER_COMMIT) |

축 1만 고려하면 외부 I/O가 트랜잭션을 잡아먹고, 축 2만 고려하면 정합성이 깨진다. **두 축의 교차점을 매번 평가**하는 것이 이 프로젝트의 트랜잭션 설계 원칙이다.

---

## 3. 의사결정 1 — 주문 생성 시 Redis 타이머 등록

### 문제 정의

주문 생성 후 Redis ZSET에 30분 타임아웃 타이머를 등록해야 한다. 미결제 주문을 자동 정리하기 위함이다. 이 등록을 트랜잭션 안에 둘 것인가, 밖에 둘 것인가.

### 시나리오별 실패 모드 분석

```
시나리오 A: 트랜잭션 안에서 Redis 등록 + 이후 DB 예외
1. 주문 저장 (DB) ✓
2. 재고 차감 (DB) ✓
3. Redis 타이머 등록 ✓
4. 이후 예외 발생 → DB 롤백
5. Redis는 롤백되지 않음 → 존재하지 않는 주문의 타이머가 잔존 ❌

시나리오 B: 트랜잭션 안에서 Redis 등록 실패
1. 주문 저장 (DB) ✓
2. 재고 차감 (DB) ✓
3. Redis 타이머 등록 실패 → 예외
4. DB 전체 롤백 → 주문도 재고도 원복 ❌ (Redis 가용성이 주문 가용성을 결정)
```

두 축으로 평가하면 명확하다.

| 후보 | 축 1 (정합성) | 축 2 (자원 점유) |
|------|---------------|-----------------|
| 트랜잭션 안 | ❌ Redis는 DB 롤백에 동조하지 않아 정합성 깨짐 | ❌ Redis 응답이 커넥션 점유 |
| 트랜잭션 밖 (메서드 끝) | ❌ DB 커밋 후 Redis 실패 시 보정 누락 | ✓ |
| **AFTER_COMMIT 이벤트** | ✓ 커밋 성공 시에만 발행, 실패 시 미발행 | ✓ 커넥션 반환 후 실행 |

### 결정: @TransactionalEventListener(AFTER_COMMIT)

```java
@Transactional
public OrderResponse placeOrder(...) {
    Order savedOrder = orderRepository.save(...);
    decreaseStockAndCreateItems(...);

    // 이벤트 발행만 — 트랜잭션 커밋 후 실행됨
    eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getOrderId()));
    return buildOrderResponse(...);
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleOrderCreated(OrderCreatedEvent event) {
    orderPaymentTimeout.registerTimeout(event.orderId());
}
```

- 트랜잭션이 커밋된 후에만 Redis 등록이 실행된다
- 트랜잭션이 롤백되면 이벤트 자체가 발행되지 않는다
- DB 커넥션은 이미 반환된 상태에서 Redis 작업이 수행된다

**핵심 근거**: Redis 등록의 정합성 요구는 **단방향**이다 — "DB 커밋이 성공한 경우에만 Redis에 기록되어야 한다." 양방향(DB↔Redis 동조)은 분산 트랜잭션 없이는 불가능하므로, 단방향 보장이 가능한 AFTER_COMMIT이 두 축 모두에서 유일한 해다.

Redis 등록 자체가 실패하는 경우는 **DB 보정 스케줄러**(1시간 주기, 30분 지난 PENDING 주문 탐색)가 보완 메커니즘으로 동작한다. 즉 1차 메커니즘(이벤트 리스너) 실패 시 2차 메커니즘(스케줄러)이 받쳐주는 **계층화된 안전장치**다.

---

## 4. 의사결정 2 — 결제 승인 시 토스 API 호출

### 문제 정의

결제 승인 시 토스 API를 호출하고, 응답에 따라 Payment/Order 상태를 변경한다. **외부 API 호출과 DB 상태 변경 사이의 트랜잭션 경계**를 어떻게 잡을 것인가.

### 시나리오별 실패 모드 분석

```
시나리오 A: 트랜잭션 밖에서 토스 호출
1. 토스 승인 API 호출 → 성공 (돈이 빠져나감)
2. @Transactional 시작
3. Payment 상태 DONE으로 변경
4. DB 저장 실패 → 롤백
5. 토스에서는 결제 완료인데 우리 DB에서는 미결제 ❌

시나리오 B: 트랜잭션 안에서 토스 호출
1. @Transactional 시작
2. Payment 조회 + 검증
3. 토스 승인 API 호출 → 성공
4. Payment 상태 DONE으로 변경
5. DB 저장 실패 → 롤백
6. 토스에서는 결제 완료인데 우리 DB에서는 미결제 ❌ (같은 문제)
```

**핵심 통찰**: 외부 API와 DB는 분산 트랜잭션이 아니므로 "토스 성공 + DB 실패" 불일치는 어떤 배치를 해도 0이 될 수 없다. 즉 의사결정 기준은 "불일치를 없애는 것"이 아니라 **"불일치 발생 빈도를 최소화하고, 발생 시 보상 비용을 줄이는 것"** 이 된다.

### 결정: 트랜잭션 안에서 호출 + 보상 로직 준비

```java
@Transactional
public PaymentResponse approvePayment(String paymentKey, String orderId, Integer amount) {
    Payment payment = paymentRepository.findByOrderPgOrderId(orderId)...;

    // 위변조 검증
    if (!payment.getRequestedAmount().equals(amount)) {
        throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
    }

    // 토스 승인 API 호출
    paymentClientFactory.getClient("TOSS").confirmPayment(paymentKey, orderId, amount);

    // 상태 변경
    payment.approve(amount);
    payment.getOrder().markPaid();

    return PaymentResponse.from(payment);
}
```

**트랜잭션 안에 둔 이유** — 두 가지 이득이 트레이드오프를 정당화한다.

1. **검증-승인의 원자성**: 위변조 검증(`requestedAmount` 비교) 직후 토스 호출까지의 구간에서 `requestedAmount`가 변경되지 않음을 트랜잭션이 보장한다. 검증과 승인이 분리되면 TOCTOU(Time-of-check to time-of-use) 취약점이 생긴다
2. **자동 롤백**: 토스 호출 실패 시 상태 변경이 자동으로 원복되어, 보상 로직 없이 일관성이 유지되는 정상 경로(happy path) 비율이 높아진다

**트레이드오프 — 의식적으로 수용**한 비용:
- 토스 응답 시간(통상 200~500ms) 동안 DB 커넥션 1개 점유
- 결제 승인은 **주문 생성 트래픽의 일부에서만 발생**(중도 이탈 사용자 제외)하므로 주문 생성보다 빈도가 낮음
- "토스 성공 + DB 실패" 케이스는 보상 로직(토스 취소 API)으로 대응하며, 발생률이 매우 낮아 운영 부담이 미미함

---

## 4.5. 의사결정 — 분리된 두 트랜잭션의 연결 매개체

### 문제 정의

주문 생성(트랜잭션 1)과 결제 승인(트랜잭션 2) 사이에 사용자의 결제 인증 시간이 흐른다. 두 트랜잭션은 서로의 컨텍스트를 모르므로, 신뢰할 수 있는 연결 매개체가 필요하다.

```
트랜잭션 1: 주문 생성 + Payment READY 저장 → 커밋 → 커넥션 반환
              ... 수초 ~ 수분 경과 ...
트랜잭션 2: Payment 조회 + 검증 + 토스 승인 + 상태 변경
```

### 해결: pgOrderId + requestedAmount를 DB에 기록

트랜잭션 1에서 두 가지를 DB에 남겨둔다.

- **pgOrderId**: UUID 기반 주문번호. 토스 결제창 → successUrl 리다이렉트 시 이 값이 돌아온다.
- **requestedAmount**: 서버가 계산한 결제 금액. 위변조 검증의 기준.

```java
// 트랜잭션 1 — placeOrder()
paymentRepository.save(Payment.create(savedOrder, "", "주문 결제", finalAmount, ...));

// 트랜잭션 2 — approvePayment()
Payment payment = paymentRepository.findByOrderPgOrderId(orderId);  // pgOrderId로 연결
if (!payment.getRequestedAmount().equals(amount)) {                 // 금액으로 검증
    throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
}
```

**설계 원리**: 분산된 트랜잭션 사이의 정합성은 **서버가 직접 쓴 불변 기록**으로만 이을 수 있다. 클라이언트나 외부 시스템에서 들어오는 값은 검증 대상이지 신뢰 대상이 아니다. `requestedAmount`가 SSOT(Single Source of Truth) 역할을 하며, 외부에서 온 `amount`는 이 SSOT와의 비교 대상으로만 사용된다.

이 구조의 비용은 명확하다 — 추가적인 영속화 컬럼과 검증 로직이 필요하다. 그러나 그 대가로 **사용자 인증 시간 동안 DB 커넥션 0개 점유**를 달성한다. 대용량 트래픽에서 이 트레이드오프는 압도적으로 유리하다.

---

## 5. 의사결정 3 — 조회 트랜잭션의 readOnly 분리

### 문제 정의

조회 API는 트래픽의 80% 이상을 차지한다. 주문 목록·상세 조회에 `@Transactional`을 어떻게 적용할 것인가 — 또는 적용하지 않을 것인가.

### 결정: @Transactional(readOnly = true)

```java
@Transactional(readOnly = true)
public OrderCursorResponse getMyOrders(Long userId, Long cursor, Integer size) {
    ...
}

@Transactional(readOnly = true)
public OrderResponse getOrder(Long userId, Long orderId) {
    ...
}
```

`readOnly = true`의 다층적 효과:

| 계층 | 효과 |
|------|------|
| Hibernate | 더티체킹 스킵 → 스냅샷 비교 제거로 메모리·CPU 절감 |
| JPA | FlushMode가 MANUAL로 변경 → 불필요한 flush 비용 제거 |
| 인프라 | 향후 MySQL Replication 환경에서 Slave 라우팅 가능 (`@Transactional` 자체가 라우팅 힌트) |

이 결정은 **현재 성능 최적화이자 미래 확장성 베이스라인**이라는 두 측면을 동시에 갖는다. 단일 DB 환경에서도 더티체킹·flush 비용이 사라지고, 향후 읽기 부하 분산 시 코드 수정 없이 라우팅이 활성화된다. 트래픽 80%를 차지하는 조회에 이 한 줄 어노테이션을 빠뜨리는 것은 **대용량 환경에서 허용할 수 없는 누락**이다.

---

## 6. 의사결정 4 — 결제 취소 시 토스 API + DB 변경

### 문제 정의

결제 취소는 `CancelPolicy` 검증 → 토스 취소 API 호출 → Payment/Order 상태 변경의 3단계로 진행된다. 의사결정 2(승인)와 구조적으로 동일한 상황이지만, **취소가 추가로 가지는 특성**(빈도, 보상 비용, 정책 검증)을 다시 평가했다.

### 평가

```
토스 취소 성공 + DB 실패 → 외부에서는 환불 완료, 내부에서는 결제 완료 상태
→ 사용자가 동일 주문을 재취소 시도 가능 → 이중 환불 위험
```

승인과 비교했을 때 **취소의 보상 비용은 더 크다**(이중 환불 가능성). 따라서 트랜잭션 경계는 더 보수적으로 잡아야 한다.

### 결정: 트랜잭션 안에서 호출 (승인과 동일 구조 + 더 강한 정당화)

```java
@Transactional
public PaymentResponse cancelPayment(Long paymentId, String cancelReason, Integer cancelAmount) {
    Payment payment = paymentRepository.findById(paymentId)...;
    Order order = payment.getOrder();

    cancelPolicy.validate(order);

    // 토스 취소 API 호출
    paymentClientFactory.getClient("TOSS")
            .cancelPayment(payment.getPaymentKey(), cancelReason, cancelAmount);

    // 상태 변경
    if (cancelAmount == null) {
        payment.cancelFull(cancelReason);
        order.cancelPaid();
    } else {
        payment.cancelPartial(cancelAmount, cancelReason);
    }

    return PaymentResponse.from(payment);
}
```

**의사결정 근거**:
- `CancelPolicy.validate()` → 토스 호출 → DB 변경의 **3단계 모두가 같은 트랜잭션 안에서 일관된 정책 컨텍스트**를 유지해야 한다. 검증 통과 시점과 호출 시점 사이에 정책이 바뀌면 부분 환불 후 정책 위반 같은 모순이 가능
- 취소 빈도는 승인보다 더 낮으므로 커넥션 점유 비용은 무시 가능
- 보상 비용이 큰 만큼 자동 롤백의 가치가 더 크다

---

## 판단 요약

| 작업 | 트랜잭션 안/밖 | 근거 |
|---|---|---|
| 주문 저장 + 재고 차감 | 안 | 원자성 필수 — 둘 중 하나만 되면 안 됨 |
| Redis 타이머 등록 | 밖 (AFTER_COMMIT) | 롤백 시 Redis 오염 방지, 실패해도 주문은 유지 |
| 토스 승인 API 호출 | 안 | 실패 시 상태 변경 롤백 필요, 빈도 낮아 허용 |
| 토스 취소 API 호출 | 안 | 승인과 동일 판단 |
| 주문 목록/상세 조회 | readOnly | 더티체킹 스킵, 읽기 전용 커넥션 |
| 결제 실패 시 주문 취소 | 안 | Payment 상태 + Order 취소 + 재고 복구가 원자적이어야 함 |

---

## 정리 — 트랜잭션은 "기본값 없이 매번 결정하는 자원"

`@Transactional`은 어노테이션 한 줄이지만, 그 한 줄은 **시스템의 동시성 한계와 정합성 보장 범위를 동시에 결정**하는 자원이다. 그래서 이번 프로젝트에서는 다음 네 가지 원칙을 트랜잭션 설계의 일관된 기준으로 삼았다.

1. **원자성이 필요한 DB 작업**은 같은 트랜잭션 — 이것이 트랜잭션의 본래 목적
2. **외부 시스템(Redis, 외부 API)** 은 두 축(정합성·자원 점유)을 동시에 평가해 분리 가능하면 분리, 불가능하면 보상 로직과 함께 안에 둠
3. **조회**는 `readOnly`로 분리 — 현재 성능 최적화 + 미래 read replica 확장의 베이스라인
4. **모든 트랜잭션은 짧게** — 커넥션 점유 시간이 곧 장애 확률

이 원칙이 의미하는 바는 단순하다. 트랜잭션은 **"기본값으로 두르는 어노테이션"이 아니라 매 작업마다 근거를 갖고 결정하는 자원**이다. 새 메서드를 짤 때마다 "이 작업이 트랜잭션 안에 있어야 하는 이유"를 한 문장으로 설명하지 못한다면, 그 어노테이션은 아직 설계가 아니라 습관이다.
