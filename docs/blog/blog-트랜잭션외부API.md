# 트랜잭션 경계 밖으로 외부 I/O를 분리한 이유 — 두 가지 문제와 이벤트 기반 해결

## 왜 이 패턴을 처음부터 강제 규칙으로 채택했나

`@Transactional`이 두르고 있는 한 메서드는 **DB 커넥션의 임대 계약**이다. 이 계약 안에서 외부 시스템 호출(외부 API, Redis, 이메일 등)이 일어나는 순간, 시스템은 두 가지 위험에 동시에 노출된다.

1. **자원 위험** — DB와 무관한 I/O가 DB 커넥션을 점유해 풀을 고갈시킨다
2. **정합성 위험** — DB 트랜잭션의 롤백이 외부 시스템에는 전파되지 않아 데이터 정합성이 깨진다

이 두 위험은 **서로 다른 종류의 실패**(가용성 vs 일관성)를 만들지만, **원인은 동일**하다 — 트랜잭션 경계 안에서 외부 I/O가 발생한다는 사실 그 자체. 따라서 해결도 단일 원칙으로 가능하다.

> **외부 I/O는 트랜잭션 경계 밖에서만 실행한다.** 단, 외부 I/O의 실행 여부는 DB 커밋 성공에 종속되어야 한다.

이 원칙을 만족하는 깔끔한 구현이 `@TransactionalEventListener(AFTER_COMMIT)`이다. 이 글은 두 위험의 구조를 분석하고, 왜 이 한 가지 패턴이 두 위험을 동시에 해결하는지의 기록이다.

## 위험 1 — 자원: DB 커넥션 점유

```java
@Transactional
public void placeOrder(Long userId, OrderCreateRequest request) {
    // 1. DB 커넥션 획득 ─────────────────────────┐
    Order order = orderRepository.save(...);       //  │
    decreaseStock(...);                            //  │ DB 커넥션 점유 중
    //                                              │
    // 2. 외부 API 호출 (2~5초 소요)                  │
    tossPaymentClient.confirmPayment(...);         //  │ ← 이 시간 동안 커넥션 낭비
    //                                              │
    // 3. Redis 타이머 등록                           │
    redisTemplate.opsForZSet().add(...);            //  │ ← 이것도 DB와 무관
    //                                              │
    return response;                               //  │
    // 트랜잭션 종료, 커넥션 반환 ──────────────────────┘
}
```

DB 작업이 0ms에 끝났더라도, 트랜잭션 종료까지 외부 API 응답 시간(2~5초)이 추가된다. 이 동안 커넥션은 **DB와 무관한 일을 기다리며 점유**된다. 즉 외부 시스템의 SLA가 우리 DB 풀의 회전율을 직접 깎아먹는 구조다.

### 대용량 트래픽에서의 영향 — 단일 외부 의존이 전체 가용성을 결정

HikariCP 기본 커넥션 풀 크기는 10개다.

```
동시 요청 100건 × 외부 API 응답 2초 = 10개 커넥션으로 100건 처리 불가
→ 91번째 요청부터 커넥션 대기
→ 대기 시간 초과 시 ConnectionTimeoutException
→ 서비스 장애
```

결과적으로 **외부 PG의 응답 지연이 상품 조회·로그인까지 멈추는 장애 전파**가 발생한다. 도메인적으로 무관한 영역이 단일 인프라 자원(커넥션 풀)을 공유하기 때문이다. 이 구조 위에서는 외부 시스템의 SLA가 우리 시스템의 SLA를 결정하게 된다 — **장애 격리가 사실상 불가능한 상태**다.

## 위험 2 — 정합성: 롤백 범위의 비대칭

```java
@Transactional
public void placeOrder(...) {
    orderRepository.save(order);           // DB 저장 성공
    decreaseStock(...);                    // 재고 차감 성공

    redisTemplate.opsForZSet().add(...);   // Redis 등록 성공

    // 이 시점에서 예외 발생하면?
    throw new RuntimeException("...");

    // DB는 롤백된다 → 주문도, 재고 차감도 취소
    // Redis는 롤백 안 된다 → 타이머가 남아있음
    // → 존재하지 않는 주문의 타임아웃이 Redis에 존재하는 정합성 문제
}
```

DB 트랜잭션의 롤백은 **DB 시스템 안에서만 유효**하다. Redis, 외부 API, 메시지 브로커는 각자의 정합성 모델을 갖고 있어, 우리 DB가 롤백된다고 해서 그들이 자동으로 되돌아가지 않는다. 즉 **트랜잭션의 보호 범위는 비대칭**이다 — 안에 있는 모든 것이 동등하게 보호되는 게 아니다.

이 비대칭은 표면적으로는 사소해 보이지만, 운영 환경에서는 **존재하지 않는 자원에 대한 알람·스케줄러·작업이 누적**되며 점진적인 데이터 오염을 만든다. 한 번에 무너지는 장애보다 추적이 더 어렵다.

## 해결 후보 1 — 서비스 클래스 분리

가장 직관적인 해법은 트랜잭션 메서드와 외부 호출 메서드를 **다른 클래스로 분리**하는 것이다.

```java
// @Transactional 없음
public OrderResponse placeOrder(Long userId, OrderCreateRequest request) {
    // 트랜잭션 안 (별도 메서드)
    OrderResponse response = orderTransactionService.createOrder(userId, request);

    // 트랜잭션 밖 — DB 커넥션 반환된 상태
    orderPaymentTimeout.registerTimeout(response.orderId());
    tossPaymentClient.confirmPayment(...);

    return response;
}
```

이 방식은 트랜잭션 경계는 정확하게 분리하지만 **추적성이 떨어진다**.

- 클래스가 2개로 늘어나며, 한 비즈니스 흐름이 두 파일에 걸쳐 분산된다
- 호출 순서가 코드 스타일에 의존하므로, 다른 개발자가 무심코 `OrderService` 안에서 외부 호출을 다시 끼워 넣을 위험이 있다
- 보상 로직이 필요할 때 "어느 클래스에 둘 것인가" 문제가 또 발생한다

즉 이 해법은 **개발자의 규율**에 정합성을 의존시킨다. 코드 리뷰와 컨벤션으로 막을 수는 있지만, 컴파일러와 런타임이 강제하는 안전망은 없다. 더 강한 보장이 필요했다.

## 해결 후보 2 — @TransactionalEventListener (채택)

Spring의 트랜잭션 동기화 메커니즘을 활용해, **외부 I/O를 트랜잭션 경계 밖으로 자동 이동**시키는 방식이다.

### 이벤트 정의

```java
public record OrderCreatedEvent(Long orderId) {}
```

### 서비스에서 이벤트 발행

```java
@Transactional
public OrderResponse placeOrder(Long userId, OrderCreateRequest request) {
    Order savedOrder = orderRepository.save(Order.create(...));
    List<OrderItem> savedItems = decreaseStockAndCreateItems(...);

    // 이벤트 발행 — 아직 실행되지 않음, 트랜잭션 커밋 후에 실행됨
    eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getOrderId()));

    return buildOrderResponse(savedOrder, savedItems);
}
// 메서드 종료 → 트랜잭션 커밋 → 커넥션 반환 → 이벤트 리스너 실행
```

### 이벤트 리스너 — 트랜잭션 커밋 후 실행

```java
@Component
public class OrderCreatedEventListener {

    private final OrderPaymentTimeout orderPaymentTimeout;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        orderPaymentTimeout.registerTimeout(event.orderId());
    }
}
```

### TransactionPhase 옵션

| Phase | 실행 시점 | 용도 |
|---|---|---|
| BEFORE_COMMIT | 커밋 직전 | 같은 트랜잭션 안에서 추가 작업 |
| AFTER_COMMIT | 커밋 직후 | 외부 API, Redis, 메일 발송 등 |
| AFTER_ROLLBACK | 롤백 직후 | 실패 알림, 보상 로직 |
| AFTER_COMPLETION | 커밋/롤백 후 | 무조건 실행 (cleanup) |

`AFTER_COMMIT`이 두 위험을 동시에 해결하는 메커니즘:

| 위험 | 해결 메커니즘 |
|------|--------------|
| 자원 점유 | 리스너 실행 시점에는 트랜잭션이 이미 커밋되어 **DB 커넥션이 반환된 상태** — 외부 I/O 동안 풀을 점유하지 않음 |
| 정합성 비대칭 | 트랜잭션이 롤백되면 이벤트 자체가 실행되지 않음 — **DB 커밋 ⇔ 외부 호출**의 단방향 종속이 자동 보장 |

## 두 해결책의 트레이드오프 비교

| 기준 | 서비스 클래스 분리 | @TransactionalEventListener |
|------|-------------------|----------------------------|
| 트랜잭션 경계 강제력 | 약함 (개발자 규율) | 강함 (Spring이 자동 관리) |
| 코드 흐름의 명시성 | ★★★ (호출 순서가 보임) | ★★ (이벤트로 간접 연결) |
| 롤백-외부호출 종속 보장 | 개발자가 직접 구현 | AFTER_COMMIT이 자동 보장 |
| 클래스 수 | 2개로 분리 | 1개 + Listener |
| 한 비즈니스 흐름의 응집도 | 분산됨 | 유지됨 |

**채택 근거**: 결제·주문 도메인은 비즈니스 흐름의 **응집도**가 추적성보다 더 중요하다. 호출 순서를 명시적으로 드러내는 이득보다, 트랜잭션 경계를 **컴파일/런타임 단위에서 강제**받는 안전성이 더 크다고 판단했다. 또한 `OrderCreatedEvent`라는 도메인 이벤트가 별도로 의미를 가지므로(향후 다른 리스너 — 알림, 통계, 추천 갱신 — 가 추가될 수 있음) 이벤트 모델이 자연스러운 도메인 표현이 된다.

## 정리 — "외부 I/O는 트랜잭션 밖에서만"이라는 단일 규칙

이 글에서 다룬 두 위험(자원 / 정합성)은 표면적으로 다르지만 **원인이 동일**하다. 따라서 해결책도 단일 원칙으로 수렴한다.

> **원칙: 트랜잭션 경계 안에서는 DB 작업만 수행한다. 외부 시스템과의 상호작용은 모두 커밋 이후로 미룬다. 단, 외부 호출의 실행 여부는 DB 커밋 성공에 종속되어야 한다.**

`@TransactionalEventListener(AFTER_COMMIT)`은 이 원칙을 **프레임워크 단위에서 강제**하는 도구다. 결제·주문 도메인 전반에서 이 규칙을 일관되게 적용한 결과, 외부 PG의 응답 지연이 우리 시스템의 가용성을 직접 결정하지 않게 됐고, Redis 잔존 데이터로 인한 정합성 오염도 원천 차단됐다. 한 줄짜리 어노테이션 선택이 아니라, **장애 격리와 정합성 비대칭을 동시에 풀어내는 단일 설계 원칙**의 적용이었다.
