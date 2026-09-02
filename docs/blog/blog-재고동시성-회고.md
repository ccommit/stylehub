# [성능 최적화 #2]재고 동시 차감을 비관적 락에서 atomic UPDATE로 — 측정으로 가설을 뒤집은 과정 (feat. 부하 테스트)

## 요약

> 재고 차감을 비관적 락에서 atomic UPDATE로 바꾸고, 측정 환경의 병목까지 걷어내 RPS를 39.9 → 352.9(8.8배)로 끌어올렸다. 음수 재고는 누적 약 30만 건 동안 0건.

처음 세운 가설은 "비관적 락으로 정합성을 잡고, 부하가 커져 락이 병목이 되면 분산 락으로 옮긴다"였다. 그런데 측정해보니 50명이 같은 옵션에 몰려도 락은 병목이 아니었다. 이 글은 그렇게 빗나간 가설들과, 측정이 그걸 어떻게 바로잡았는지에 대한 기록이다. 수치는 직접 돌린 부하 테스트([주문결제-성능측정.md](주문결제-성능측정.md))에서 가져왔다.

***

## 0. 재고 차감에 왜 락이 필요한가

차감 코드를 짤 때 처음 막힌 건 격리 수준이었다. 두 요청이 같은 재고 10을 각자 읽고 각자 9로 줄이면, 2개가 팔렸는데 재고는 1만 줄어드는 Lost Update가 난다.

> "트랜잭션 격리 수준이 이걸 막아주지 않나?"

MySQL 기본인 `REPEATABLE READ`가 보장하는 건 한 트랜잭션 안에서의 일관성이다. 서로 다른 두 트랜잭션이 같은 행을 각자 읽는 경쟁은 막지 않는다. 격리 수준이 지키는 건 내 트랜잭션의 일관성이지 트랜잭션 간 race가 아니다. 그래서 락이 필요했다.

***

## 1. 락 후보 중 왜 비관적 락인가

| 후보 | 판단 |
|---|---|
| `synchronized` | 단일 JVM 한정. 다중 인스턴스로 가면 무력화돼서 지금 쓰면 부채 |
| 낙관적 락(`@Version`) | 인기 상품은 충돌이 잦아 retry 폭주 |
| DB 비관적 락 | 정합성 확실 + 다중 인스턴스 OK. 채택 |
| 분산 락(Redis) | 운영 복잡도. 다음 단계로 보류 |

정합성이 우선이었고, 단일 서버 단계지만 나중에 버려질 `synchronized`를 지금 쓰고 싶지 않았다. 그래서 비관적 락을 골랐고, "지금은 비관적 락, 부족하면 분산 락"을 다음 단계로 적어뒀다. 이 가정이 측정을 거치며 어떻게 바뀌는지가 이 글의 본문이다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT po FROM ProductOption po WHERE po.productOptionId = :optionId")
Optional<ProductOption> findByIdWithLock(@Param("optionId") Long optionId);
```

***

## 2. Deadlock — 차감과 복구의 락 순서를 맞추기

한 주문에 여러 옵션이 들어올 수 있어서, 두 주문이 락 잡는 순서가 엇갈리면 deadlock이 난다.

```
요청 A: 옵션 1 락 → 옵션 2 락 시도 (대기)
요청 B: 옵션 2 락 → 옵션 1 락 시도 (대기)
→ DEADLOCK
```

모든 주문이 optionId 오름차순으로 락을 잡게 했다. `TreeMap`을 쓰면 정렬과 중복 옵션 수량 합산이 같이 처리된다.

```java
private List<OrderItemRequest> mergeAndSort(List<OrderItemRequest> requests) {
    Map<Long, Integer> merged = new TreeMap<>();  // 오름차순 + 중복 수량 합산
    for (OrderItemRequest req : requests) {
        merged.merge(req.productOptionId(), req.quantity(), Integer::sum);
    }
    // 정렬된 순서로 재구성
}
```

여기서 한 번 막혔다. 차감만 정렬해서는 부족했다. 주문 취소로 재고를 복구할 때도 같은 순서로 락을 잡아야 한다. 차감은 오름차순인데 복구가 다른 순서로 돌면 둘이 엇갈려 또 deadlock이 난다. 이 정렬을 빼먹어서 테스트가 깨진 적이 있다.

```java
private void restoreStock(Long orderId) {
    List<OrderItem> items = orderItemRepository.findByOrderIdWithDetails(orderId);
    // 차감과 동일하게 optionId 오름차순으로 락 획득
    items.sort((a, b) -> Long.compare(
            a.getProductOption().getProductOptionId(),
            b.getProductOption().getProductOptionId()));
    ...
}
```

> 락 순서는 그 락을 건드리는 모든 경로에서 같아야 한다.

***

## 3. "동일 옵션에 50명이 몰리면 락이 병목이 될까?"

50 users가 hot option 하나에 몰리도록 설정해 측정했다(측정 2).

| 지표 | 값 |
|---|---|
| RPS | 39.6 (이론 상한 40의 99%) |
| P99 | 130 ms |
| Max | 459 ms |
| 음수 재고 | 0 |

락이 직렬화하는데도 처리량이 거의 안 떨어졌다. 트랜잭션이 ms 단위로 짧아 락 큐가 빠르게 빠졌고, 부하 클라이언트의 `wait_time`(평균 1.25초)이 요청을 시간차로 흩어서 완벽한 동시 도달이 드물었다.

> 50 users 수준에서 락은 병목이 아니었다. "락이 병목이니 분산 락으로"라는 다음 가정의 전제가 여기서 흔들렸다.

***

## 4. "락을 아예 없앨 수 있을까?" — atomic UPDATE

락이 당장 병목은 아니어도 트래픽이 커지면 락 점유 시간이 결국 천장이 된다. 그래서 질문을 바꿨다. 이 영역에 락이 꼭 필요한가? 재고 차감은 조건이 맞으면 숫자 하나를 줄이는 단일 연산이라, DB에 한 문장으로 시키면 된다.

```java
@Modifying
@Query("UPDATE ProductOption po SET po.stockQuantity = po.stockQuantity - :qty " +
       "WHERE po.productOptionId = :optionId AND po.stockQuantity >= :qty")
int decreaseStockAtomic(@Param("optionId") Long optionId, @Param("qty") int qty);
```

`WHERE stock >= qty`가 음수 재고 방지와 SOLD_OUT 판정을 같이 한다(0건 반환이면 부족). 락 점유 시간은 0에 가깝고 정합성은 그대로다. 같은 조건으로 다시 측정했다(측정 4).

| 지표 | 비관적 락(측정 2) | atomic UPDATE(측정 4) |
|---|---|---|
| RPS | 39.6 | 39.9 |
| Max | 459 ms | 350 ms |
| 음수 재고 | 0 | 0 |

RPS가 39.9로 거의 그대로였다. 여기서 변경을 되돌렸으면 멀쩡한 코드를 버릴 뻔했다.

***

## 5. "코드 문제일까, 측정 문제일까?"

되돌리기 전에 부하 도구 설정을 봤다.

```
wait_time = between(0.5, 2.0)  → 평균 1.25초
이론 RPS 상한 = 50 users / 1.25초 = 40
```

측정값 자체가 RPS 40에서 막혀 있었다. 비관적 락(39.6)도 atomic UPDATE(39.9)도 같은 천장에 눌려서 차이가 안 보였던 것이다.

> 락이 RPS 39.6의 천장이라고 본 것부터가 오해였고, 천장은 wait_time이었다.

`wait_time`을 줄여 측정 천장을 풀고(시스템 코드가 아니라 측정 설정이라 production과 무관하다) JDBC 튜닝(prepared statement 캐싱, Hibernate batch)을 함께 적용해 다시 측정했다(측정 5).

| 지표 | 측정 4 | 측정 5 | 변화 |
|---|---|---|---|
| RPS | 39.9 | 352.9 | 8.8배 |
| P99 | 210 ms | 70 ms | 3배 단축 |
| 음수 재고 | 0 | 0 | 유지 |

8.8배가 됐는데도 음수 재고는 0건이었다. 측정 4의 39.9는 시스템 한계가 아니라 부하 클라이언트의 한계였다. 측정값을 답으로 받기 전에 "이 숫자가 무엇의 한계를 보고 있는가"를 먼저 물어야 한다.

***

## 6. "atomic UPDATE면 끝일까?" — 동일 row contention

부하를 더 올리니 다른 그림이 나왔다(측정 6, 7).

| 측정 | Users | RPS | Median |
|---|---|---|---|
| 5 | 50 | 352.9 | 10 ms |
| 6 | 100 | 308 | 20 ms |
| 7 | 200 | 502 | 89 ms |

50에서 100으로 user를 늘리니 RPS가 오히려 떨어졌고(353 → 308), 200에서는 RPS가 회복됐지만 응답 시간이 9배로 늘었다. atomic UPDATE는 코드로 락을 안 걸지만, DB가 동일 row의 UPDATE를 row-lock으로 자동 직렬화한다. 같은 hot option에 몰리면 그 한 row의 UPDATE 큐가 길어진다.

> atomic UPDATE는 비관적 락보다 락 점유 시간이 짧을 뿐, 동일 row contention 한계는 같다. 이걸 넘으려면 단일 row 자체를 분해(인기 옵션 샤딩)해야 한다.

측정 6 첫 시도에서 5xx가 13% 떠서 시스템 한계인 줄 알았는데, hot은 0%이고 random만 67% 실패였다. 시드 데이터의 일부 옵션이 존재하지 않는 store를 참조하는 dangling FK였고, 그 비율이 fail 비율과 정확히 같았다. 측정 데이터의 무결성도 한계를 논하기 전에 확인해야 한다.

***

## 7. 분산 락을 쓰지 않은 이유

처음 가정("부족하면 분산 락")을 측정이 뒤집었다.

- 50 users에서 락은 병목이 아니었다(측정 2)
- atomic UPDATE로 락 점유 시간 0, RPS 8.8배(측정 5)
- 동일 row contention 한계는 atomic UPDATE도 비관적 락과 같다(측정 6, 7)

분산 락은 애플리케이션 레벨에서 여러 단계를 묶어 직렬화하는 도구다. 재고 차감은 이미 DB가 row lock으로 직렬화하니, 그 위에 분산 락을 얹으면 Redis 왕복 비용만 추가되고 contention 한계는 그대로다. 분산 락이 맞는 자리는 여러 단계가 묶인 비즈니스 단위 락(선착순 쿠폰 발급 등)이고 재고 차감은 아니다. 그래서 분산 락 인프라(`@DistributedLock`)는 만들어 둔 채 재고에는 쓰지 않았다.

atomic UPDATE에도 비용은 있다. OrderItem을 만들려면 차감 후 ProductOption을 한 번 더 SELECT해야 하고, 동일 row contention 한계는 비관적 락과 같다. 최종 답이 아니라 샤딩 전의 한 지점이다.

***

## 마치며

가설이 측정마다 한 번씩 바뀌었다.

- "동일 옵션 집중이면 락이 RPS를 0으로 만든다" → 39.6, 이론치 99%. 락은 병목이 아니었다
- "atomic UPDATE로 바꾸면 RPS가 오른다" → 39.9, 그대로. 천장은 측정 도구의 wait_time이었다
- "부족하면 분산 락" → 동일 row contention 한계는 같음. 다음은 샤딩이다

> 측정값을 답으로 받기 전에 "이 숫자가 무엇의 한계를 보고 있는가"를 매번 다시 묻게 됐다.

그 질문이 없었으면 락을 병목으로 오해하고, 멀쩡한 atomic UPDATE를 버리고, 필요 없는 분산 락을 넣었을 것이다. 도구를 늘리는 대신 줄이는 쪽으로 정리한 게 이번 작업의 결과다.

남은 과제는 동일 row contention을 넘는 인기 옵션 샤딩과 별도 머신 분리 측정이다. 단일 머신에 앱·DB·Redis·부하 클라이언트가 함께 돌면 200 users 부근부터 측정값이 시스템 한계인지 환경 한계인지 구분되지 않았다(측정 7에서 Locust CPU 90% 경고).
