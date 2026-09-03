# Redis ZSET으로 결제 타임아웃 구현 — 원자성·확장성·장애 내성을 동시에 만족하는 설계

## 왜 결제 타임아웃을 단순한 스케줄러가 아닌 "삼중 요건"으로 풀었나

결제 타임아웃은 표면적으로는 "30분 지난 미결제 주문을 자동 취소한다"는 단일 기능이다. 그러나 이 기능을 대용량 트래픽 + 다중 서버 환경에서 **실제 production-ready로 만들려면** 다음 세 가지 요건이 동시에 만족되어야 한다.

| 요건 | 만족하지 못할 때의 결과 |
|------|---------------------|
| **원자성** (조회+삭제 race-free) | 다중 서버가 같은 만료 주문을 중복 처리해 이중 취소 |
| **확장성** (대량 주문에서도 효율) | DB 풀스캔이 트래픽 증가에 따라 선형으로 부하 |
| **장애 내성** (Redis 장애 시 보정) | Redis 장애 시 영원히 PENDING으로 남는 주문 발생 |

세 요건은 **각자 다른 메커니즘이 필요**하다. 단일 도구로 모두 풀리지 않는다. 따라서 이 작업은 처음부터 "어떤 메커니즘이 어떤 요건을 담당하는가"를 명시한 다층 설계로 접근했다.

| 메커니즘 | 담당 요건 |
|---------|---------|
| Redis ZSET (score = 만료 시각) | 확장성 (O(log N) 조회) |
| Lua script (조회 + 삭제 한 호출) | 원자성 (다중 서버 중복 방지) |
| `@TransactionalEventListener(AFTER_COMMIT)` | 정합성 (DB 롤백 시 Redis 오염 차단) |
| DB 보정 스케줄러 (1시간 주기) | 장애 내성 (Redis 장애 보완) |

이 글은 각 메커니즘의 동작 원리와, 왜 다른 대안이 아닌 이 메커니즘을 선택했는지의 의사결정 기록이다.

## 문제 상황

```
1. 사용자 A가 상품을 주문한다 → 재고 10 → 9로 차감
2. 결제 대기 상태(PENDING)로 30분 유지
3. 사용자 A가 결제를 안 한다
4. 30분 동안 재고 1개가 묶여 있어서 다른 사용자가 구매 불가
```

30분 내에 결제가 완료되지 않으면 자동으로 주문을 취소하고 재고를 복구해야 한다.

## 첫 번째 결정 — 저장소 선택: 왜 DB 풀스캔이 아닌 Redis ZSET인가

### DB 풀스캔 방식의 누적 비용

```sql
SELECT * FROM orders WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL 30 MINUTE;
```

이 쿼리의 비용은 **주문 누적량에 비례**한다. 인덱스를 (`status`, `created_at`)으로 걸어도, 시간이 흐르며 PENDING이 아니었지만 인덱스 페이지를 거쳐야 하는 row가 누적된다. 즉 **트래픽이 늘수록 타임아웃 처리 자체가 부하의 원천**이 되는 구조다.

또한 1분 주기 폴링이라는 시간 제약이 있다. DB 부하가 폴링 주기 이상으로 늘어나면, 타임아웃 처리가 다음 폴링과 겹쳐 race가 발생할 수 있다.

### Redis ZSET이 본질적으로 적합한 이유

ZSET(Sorted Set)은 score로 정렬된 집합이다. score에 만료 시각(timestamp)을 넣으면, 현재 시각 이전의 데이터를 **O(log N)** 으로 조회할 수 있다.

```
ZADD order:timeout {만료시각} {orderId}
ZRANGEBYSCORE order:timeout 0 {현재시각}  → 만료된 주문 ID 목록
```

ZSET의 본질적 가치: **데이터가 자료구조 차원에서 이미 정렬되어 있으므로, "지금 시각 이전의 데이터"를 찾는 비용이 데이터 양과 무관**하다. DB 인덱스 조회와 달리 PENDING이 아닌 row를 거치지 않는다 — 만료된 주문만 ZSET에 남아 있기 때문이다.

## 구현

### 1. 타이머 등록 — 주문 생성 시

```java
@Component
public class OrderPaymentTimeout {

    private static final long TIMEOUT_MILLIS = 30 * 60 * 1000; // 30분

    public void registerTimeout(Long orderId) {
        double expireAt = System.currentTimeMillis() + TIMEOUT_MILLIS;
        redisTemplate.opsForZSet().add(
                "order:timeout",
                String.valueOf(orderId),
                expireAt  // score = 현재시각 + 30분
        );
    }

    public void removeTimeout(Long orderId) {
        redisTemplate.opsForZSet().remove("order:timeout", String.valueOf(orderId));
    }
}
```

주문 생성 시 `registerTimeout()`, 결제 완료 또는 취소 시 `removeTimeout()`을 호출한다.

### 2. 이벤트 기반 등록 — 트랜잭션 커밋 후

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

### 두 번째 결정 — 왜 AFTER_COMMIT인가

타이머 등록 시점을 결정할 때 후보 셋을 비교했다.

| 후보 | 정합성 위험 |
|------|-----------|
| 트랜잭션 안에서 Redis 등록 | DB 롤백 시 Redis만 남아 "존재하지 않는 주문의 타이머" 발생 |
| 메서드 끝(트랜잭션 후) 직접 호출 | DB 커밋 후 Redis 호출 실패 시 보정 누락 + 클래스 분리 부담 |
| **AFTER_COMMIT 이벤트** | 커밋 성공 시에만 발행 → 정합성 단방향 보장 |

AFTER_COMMIT 이벤트의 본질적 가치는 **"DB 커밋 ⇔ Redis 등록"의 단방향 종속을 프레임워크가 자동 보장**한다는 점이다. 개발자 규율에 의존하지 않고 Spring의 트랜잭션 동기화 메커니즘이 강제한다. 만약 DB가 롤백되면 이벤트 자체가 발행되지 않아 Redis 오염이 원천 차단된다. 만약 Redis 등록이 실패하더라도(드물지만 가능) **DB 보정 스케줄러**가 2차 안전장치로 받쳐준다.

### 3. 만료 주문 폴링 — 1분마다 스케줄러 실행

```java
@Scheduled(fixedDelay = 60000)
public void cancelExpiredOrders() {
    long now = System.currentTimeMillis();

    List<String> expiredOrderIds = redisTemplate.execute(
            FETCH_AND_REMOVE_SCRIPT,
            Collections.singletonList("order:timeout"),
            String.valueOf(now),
            String.valueOf(BATCH_SIZE)
    );

    for (String orderIdStr : expiredOrderIds) {
        orderService.cancelOrder(Long.valueOf(orderIdStr));
    }
}
```

## 세 번째 결정 — Lua 스크립트로 조회+삭제를 한 호출에 묶기

### ZRANGEBYSCORE + ZREM을 분리할 때의 race 시나리오

```
서버 A: ZRANGEBYSCORE → [주문1, 주문2]
서버 B: ZRANGEBYSCORE → [주문1, 주문2]  ← 같은 주문을 중복 조회
서버 A: ZREM 주문1, 주문2
서버 B: cancelOrder(주문1)  ← 이미 취소된 주문을 다시 취소 시도
```

**다중 서버 환경에서는 조회와 삭제 사이의 시간 간격이 곧 race 윈도우**다. 이 윈도우 안에 다른 서버가 같은 ZRANGEBYSCORE를 발행하면 동일 주문이 두 번 처리된다 — 즉 사용자 입장에서 이중 취소 알람·이중 환불 처리 같은 정합성 손상이 발생한다.

### Lua 스크립트의 본질적 가치 — race 윈도우의 제거

```lua
local orders = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
if #orders > 0 then
    redis.call('ZREM', KEYS[1], unpack(orders))
end
return orders
```

Redis는 단일 스레드 모델이다. Lua 스크립트가 실행되는 동안 **다른 모든 명령이 차단**된다. 따라서 "조회한 주문 = 삭제한 주문"이 원자적으로 보장된다 — race 윈도우 자체가 존재하지 않게 된다. 다중 서버 환경에서 어느 서버가 호출하든 결과가 동일하다.

이 패턴의 일반 원리: **다중 클라이언트가 공유하는 자원에 대한 "조회 → 결정 → 변경"이 atomicity가 필요할 때, 그 세 단계를 단일 호출로 묶을 수 있는 도구를 찾아야 한다.** Redis Lua script는 그 도구의 한 형태이며, DB의 atomic UPDATE도 같은 원리의 다른 구현이다.

## 네 번째 결정 — DB 보정 스케줄러로 Redis 장애 내성 확보

Redis도 결국 외부 시스템이며, 장애가 발생할 수 있다. Redis가 죽으면 타이머가 등록되지 못한 주문이 영원히 PENDING으로 남는다. 따라서 **Redis에 단일 의존하지 않는 보정 메커니즘**이 필요하다.

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

두 스케줄러의 역할 분담:

| 스케줄러 | 주기 | 역할 |
|---------|-----|------|
| Redis 폴링 (1분) | 1분 | 1차 방어 — 정상 경로의 효율적 처리 |
| DB 보정 (1시간) | 1시간 | 2차 방어 — Redis 등록 누락·장애 보완 |

이 다층 구조의 핵심: **1차 메커니즘이 효율(O(log N))을 담당하고, 2차 메커니즘이 신뢰성을 담당**한다. 두 요건은 트레이드오프가 있으므로, 단일 메커니즘으로 둘 다 만족시키려 하면 한쪽이 약해진다. 분리해서 각자 최적화하는 것이 더 견고한 답이다.

## 전체 흐름 정리

```
주문 생성 → 재고 차감 → 트랜잭션 커밋 → Redis ZSET에 타이머 등록 (30분)
                                            │
              ┌────────────────────────────────┤
              │                                │
         결제 완료                          30분 경과
              │                                │
    타이머 제거 + PAID              스케줄러가 감지 (Lua 원자적 처리)
                                               │
                                    주문 취소 + 재고 복구 + CANCELLED
```

## 정리 — 네 가지 메커니즘이 각자의 요건을 분담하는 다층 설계

이 작업의 핵심은 단일 도구가 아니라 **각 요건에 정확히 매핑된 4개 메커니즘의 조합**이다.

| 요건 | 담당 메커니즘 | 본질적 가치 |
|------|------------|---------|
| 확장성 (대량 주문에서 효율) | Redis ZSET | O(log N) 정렬 자료구조 |
| 원자성 (다중 서버 중복 방지) | Lua script | 단일 스레드 atomic 실행 |
| 정합성 (DB 롤백 시 Redis 오염 방지) | AFTER_COMMIT 이벤트 | 단방향 종속의 프레임워크 강제 |
| 장애 내성 (Redis 장애 보완) | DB 보정 스케줄러 | 1차/2차 메커니즘 분리 |

이 다층 설계의 일반 원리:

> **production-grade 시스템은 단일 도구의 우아한 사용이 아니라, 각 요건에 정확히 매핑된 도구의 조합으로 만들어진다.** 한 도구로 모든 요건을 풀려는 시도는 어느 한 요건에서 타협을 강요하고, 결국 production에서 무너지는 지점이 된다.

이 작업에서 던진 핵심 질문은 "Redis로 뭘 할 수 있는가"가 아니라 **"이 기능을 production에 안전하게 두려면 어떤 요건들이 동시에 만족되어야 하는가"** 였다. 요건을 먼저 명시하면 도구는 자연스럽게 따라온다.
