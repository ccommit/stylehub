# 동시성 제어 — 정합성부터 잡고, 분산 락은 일부러 미뤄둔 이야기

## 들어가며 — 이 글을 쓰게 된 계기

재고와 쿠폰은 틀리면 바로 사고가 되는 숫자다. 10개 남은 상품에 11명이 결제되거나, 100장 한정 쿠폰이 101장 발급되면 그대로 장애다. 그래서 이 두 영역은 처음부터 "동작하게"가 아니라 "동시에 들어와도 절대 안 틀리게"가 목표였다.

동시성을 공부할 때 머릿속에 정답처럼 박혀 있던 흐름이 하나 있었다.

> **"비관적 락으로 시작하고, 트래픽이 커지면 분산 락(Redis)으로 옮긴다."**

그래서 그 시나리오를 그대로 따라갈 생각이었다. 비관적 락으로 정합성을 잡고, 다음 단계를 위해 `@DistributedLock` 분산 락 인프라까지 직접 만들어뒀다. 남은 건 "부하를 올려서 분산 락이 더 낫다는 걸 보여주는 것"뿐이라고 생각했다.

그런데 막상 분산 락을 붙이려다 멈췄다. **"측정도 안 해보고, 통념만 믿고 도구를 하나 더 얹는 게 맞나?"**

이 글은 비관적 락으로 정합성을 먼저 확보한 과정과, **분산 락 인프라를 만들어 두고도 아직 적용하지 않기로 한 판단**에 대한 기록이다. 그래서 미리 밝혀둔다 — 이 글엔 "분산 락이 몇 배 빨랐다" 같은 수치가 없다. 아직 부하 측정으로 검증하지 않았고, 적용은 그 다음 단계로 의도적으로 미뤄둔 상태이기 때문이다.

---

## 1. 첫 선택은 비관적 락 — 정합성부터 확실히

가장 먼저 떠올린 답은 비관적 락이었다. 가장 직관적이고 정합성도 확실하니까. 재고와 쿠폰 모두 같은 방식으로 잡았다 — `SELECT ... FOR UPDATE`로 해당 행에 배타적 락을 걸어, 한 트랜잭션이 끝날 때까지 다른 요청이 그 행을 못 건드리게 한다.

```java
// ProductOptionRepository — 재고
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT po FROM ProductOption po JOIN FETCH po.product p JOIN FETCH p.user " +
       "WHERE po.productOptionId = :optionId")
Optional<ProductOption> findByIdWithLock(@Param("optionId") Long optionId);
```

```java
// CouponService — 선착순 쿠폰
@Transactional
public void issueCoupon(User user, Long couponEventId) {
    CouponEvent event = couponEventRepository.findByIdWithLock(couponEventId)  // SELECT FOR UPDATE
            .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

    couponValidator.validateIssuable(event);
    checkDuplicateIssue(user.getUserId(), couponEventId);
    event.increaseIssuedCount();                 // 카운터 증가 + SOLD_OUT 판정
    userCouponRepository.save(UserCoupon.create(user, event));
}
```

재고와 쿠폰을 같은 도구로 잡은 데는 이유가 있다. 쿠폰 발급은 *검증(활성/기간/중복) + 카운터 증가 + 발급 INSERT*가 한 묶음이라, 단일 SQL로 환원할 수 없는 **여러 단계의 원자성**이 필요했다. 이런 영역은 비관적 락이 교과서적으로 들어맞는다. 정합성만 보면 비관적 락은 확실한 출발점이었다.

---

## 2. 비관적 락을 쓰며 만난 진짜 함정 — Deadlock

문제는 락을 쓰자마자 드러났다. 한 주문에 여러 옵션이 들어올 수 있다는 점이다("빨간 티셔츠 M + 파란 바지 L"). 그러면 한 트랜잭션이 락을 여러 개 잡는데, 두 주문이 동시에 들어오면서 잡는 순서가 엇갈리면 deadlock이 난다.

```
요청 A: 옵션 1 락 획득 → 옵션 2 락 시도 (대기)
요청 B: 옵션 2 락 획득 → 옵션 1 락 시도 (대기)
→ 서로 상대가 쥔 락을 기다림 → DEADLOCK
```

해법 자체는 단순했다 — **모두가 같은 순서로 락을 잡게** 만드는 것. 같은 옵션이 중복으로 들어오면 수량을 합치고, optionId 오름차순으로 정렬한 뒤 차례로 차감했다.

```java
private List<OrderDetailRequest> mergeAndSort(List<OrderDetailRequest> detailRequests) {
    Map<Long, Integer> merged = new TreeMap<>();  // TreeMap → 자동 오름차순
    for (OrderDetailRequest request : detailRequests) {
        merged.merge(request.productOptionId(), request.quantity(), Integer::sum);
    }
    // ... 정렬된 순서로 재구성
}
```

여기서 정작 중요한 깨달음은 그 다음이었다. 차감만 순서를 맞추면 끝이 아니었다. **주문 취소로 재고를 복구할 때도 같은 순서**여야 했다. 차감은 오름차순인데 복구가 다른 순서로 돌면, 차감 중인 트랜잭션과 복구 중인 트랜잭션이 엇갈려 또 deadlock이 날 수 있었다.

```java
private void restoreStock(Long orderId) {
    List<OrderDetail> details = orderDetailRepository.findByOrderIdWithDetails(orderId);

    // deadlock 방지를 위해 차감과 동일하게 optionId 오름차순으로 락 획득
    details.sort((a, b) -> Long.compare(
            a.getProductOption().getProductOptionId(),
            b.getProductOption().getProductOptionId()));

    for (OrderDetail detail : details) {
        productPort.increaseStock(detail.getProductOption().getProductOptionId(), detail.getQuantity());
    }
}
```

이때 배운 건, 락 순서는 한 메서드 안에서가 아니라 **그 락을 건드리는 모든 경로에서 일관**돼야 한다는 점이었다. 차감과 복구를 따로 보면 둘 다 맞는데, 둘을 같이 놓고 봐야 deadlock이 보인다. 락은 정합성을 주는 대신, 이런 "전역적 순서"라는 새로운 책임을 지운다.

---

## 3. 계층을 나눈 이유 — 도메인 서비스는 자기 도메인만 알게

쿠폰 발급에서 한 가지 더 신경 쓴 건 계층 분리였다. 처음엔 `CouponService`가 발급 로직과 함께 User 조회·스토어 권한 검증까지 했는데, 그러면 쿠폰 도메인 서비스가 User 도메인(`UserPort`)까지 알게 되면서 책임이 번진다.

그래서 오케스트레이션(권한 검증, User 조회)은 `CouponApplicationService`로 올리고, `CouponService`는 자기 도메인 로직(비관적 락 + 발급)만 알도록 분리했다.

```java
// CouponApplicationService — 유스케이스 오케스트레이션
@Transactional
public void issueCoupon(Long userId, Long couponEventId) {
    User user = userPort.findUserById(userId);   // Application 관심사
    couponService.issueCoupon(user, couponEventId);  // 도메인에 위임
}
```

이 분리는 단순한 정리가 아니라, **나중에 동시성 도구를 바꿀 때 손댈 지점을 한 곳으로 모아두는** 효과도 있었다. 발급의 동시성 제어를 분산 락으로 옮기든 무엇으로 옮기든, 오케스트레이션 계층 한 곳만 보면 된다.

---

## 4. 분산 락 인프라를 만들고도, 아직 안 붙인 이유

통념의 다음 수순은 분산 락이었다. 비관적 락은 락 대기 동안 DB 커넥션을 물고 있어서, 인기 상품·인기 쿠폰 한 row에 트래픽이 몰리면 커넥션 풀이 고갈되며 무관한 API까지 멈출 수 있다. 락 대기를 DB가 아니라 Redis에서 처리하면 적어도 커넥션은 안 물고 있게 된다.

그래서 `@DistributedLock` 인프라를 직접 만들어뒀다. SETNX로 락을 잡고, SpEL로 동적 락 키를 만들고, UUID로 자기 락만 해제하고, 못 잡으면 50ms 간격으로 폴링한다.

```java
private boolean tryLock(String key, String value, Duration leaseTime, long waitTime, TimeUnit unit) {
    long deadline = System.currentTimeMillis() + unit.toMillis(waitTime);
    while (System.currentTimeMillis() < deadline) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value, leaseTime);  // SETNX
        if (Boolean.TRUE.equals(ok)) return true;
        Thread.sleep(50);  // 50ms 폴링
    }
    return false;
}
```

그런데 인프라를 다 만들어 놓고, **어디에도 붙이지 않았다.** 코드에는 적용 대신 TODO만 남겼다.

```java
// CouponService:   // TODO: 성능 테스트 후 분산락 적용예정
// ProductService:  // TODO: 대용량 트래픽 대응 시 Redis DECR 원자적 연산으로 전환 예정
```

여기서 멈춘 데는 두 가지 이유가 있다.

**첫째, 측정 없이 도구를 늘리는 게 싫었다.** 분산 락은 Redis 왕복 비용과 운영 복잡도(leaseTime, GC pause 중 락 만료, 자기 락 식별)라는 분명한 비용을 추가한다. 그 비용을 정당화하려면 "비관적 락 대비 실제로 이만큼 나아진다"는 측정이 있어야 하는데, 아직 그 측정을 못 했다. *"인프라가 있으니까 쓴다"가 아니라 "측정으로 정당화될 때 쓴다"* 쪽으로 판단했다.

**둘째, 내가 만든 SETNX 폴링 구현의 한계를 알고 있다.** 이 방식은 공정성(fairness)이 없다. 여러 요청이 50ms마다 SETNX를 던지며 경쟁하는 구조라, 운 나쁜 요청은 폴링 타이밍을 계속 놓쳐 대기가 길어질 수 있다. Redisson의 pub/sub 기반 fair lock처럼 "락이 풀리면 줄 선 순서대로 깨우는" 정교함이 없다. 이런 한계를 가진 구현을 측정도 없이 결제·발급 같은 핵심 경로에 올리는 건 조심스러웠다.

그래서 현재 재고와 쿠폰은 **둘 다 비관적 락 상태로 두고**, 분산 락(과 재고 쪽 Redis DECR 전환)은 "측정으로 검증한 뒤 결정"으로 의식적으로 미뤄뒀다.

---

## 마치며

지금 StyleHub의 상태를 정직하게 적으면 이렇다.

- 재고 차감과 쿠폰 발급은 **둘 다 비관적 락**으로 정합성을 확보했다.
- 다중 옵션 차감의 deadlock은 **차감과 복구 양쪽의 락 순서를 optionId 오름차순으로 통일**해 막았다.
- 분산 락 인프라(`@DistributedLock`)는 만들어 뒀지만 **아직 어디에도 적용하지 않았다.** TODO로만 남겨둔 상태다.

이번 작업에서 내가 얻은 건 세 가지다.

1. **비관적 락은 정합성 면에서 가장 확실한 첫 선택**이다. deadlock이라는 함정만 락 순서로 잡으면 견고하다.
2. **락 순서의 일관성은 한 메서드가 아니라, 그 락을 만지는 모든 경로에서** 지켜야 한다. 차감만 보면 안 보이고, 복구까지 같이 놓고 봐야 보인다.
3. **인프라를 만드는 결정과 그걸 쓰는 결정은 다르다.** 도구를 늘리는 건 그 자체로 비용이고, 측정으로 정당화되기 전까지 미루는 것도 하나의 설계다.

남은 과제는 열어둔다 — 재고는 Redis DECR 원자 연산으로, 쿠폰은 분산 락으로 옮기는 것. 단, *부하 측정으로 비관적 락 대비 이득을 확인한 뒤에.* 그 측정을 마치면 이 글에 빠져 있는 수치를 채워 넣을 생각이다. 통념의 다음 단계를 "당연히 그렇겠지"로 받아들이지 않고, 측정으로 직접 확인하고 나서 넘어가는 것 — 그게 이 작업에서 내가 지키고 싶은 태도다.
