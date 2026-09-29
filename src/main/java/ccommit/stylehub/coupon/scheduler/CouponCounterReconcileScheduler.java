package ccommit.stylehub.coupon.scheduler;

import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.CouponIssueCounter;
import ccommit.stylehub.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * Redis 예약 뒤 DB 커밋 전에 서버가 멈추거나 보상이 실패해 샌 쿠폰 자리를 찾아 카운터를 DB 기준으로 되돌린다.
 * 새면 Redis 남은 수량이 DB 보다 작아져, DB 에 남은 쿠폰이 있어도 매진으로 거절되고 샌 사용자는 이미 받은 것으로 막힌다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class CouponCounterReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(CouponCounterReconcileScheduler.class);

    private final CouponEventRepository couponEventRepository;
    private final CouponIssueCounter couponIssueCounter;
    private final CouponService couponService;

    // 직전 회차에 Redis 가 DB 보다 적게 남았다고 본 이벤트와 그때의 값. 서버마다 따로 두며, 같은 이벤트를 여러 서버가 재동기화해도 결과는 같다.
    private final Map<Long, Observation> suspected = new ConcurrentHashMap<>();

    // 진행 중인 발급은 Redis 예약과 DB 커밋 사이에 잠깐 Redis 가 적게 남으므로, 두 회차 연속 같은 값으로 어긋날 때만 샌 것으로 본다.
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void reconcileActiveEvents() {
        List<CouponEvent> activeEvents = couponEventRepository.findActiveCouponEvents(LocalDateTime.now());
        Set<Long> activeIds = activeEvents.stream().map(CouponEvent::getCouponEventId).collect(Collectors.toSet());
        suspected.keySet().retainAll(activeIds);

        for (CouponEvent event : activeEvents) {
            try {
                reconcile(event);
            } catch (RuntimeException e) {
                log.warn("쿠폰 수량 대조 실패 — 다음 회차에 다시 확인: couponEventId={}, cause={}", event.getCouponEventId(), e.toString());
            }
        }
    }

    private void reconcile(CouponEvent event) {
        Long eventId = event.getCouponEventId();
        Long redisRemaining = couponIssueCounter.remaining(eventId);
        int dbRemaining = event.remainingIssueCount();

        if (redisRemaining == null || redisRemaining >= dbRemaining) {
            suspected.remove(eventId);
            return;
        }

        Observation current = new Observation(redisRemaining, event.getIssuedCount());
        Observation previous = suspected.put(eventId, current);
        if (current.equals(previous)) {
            couponService.resyncIssueCounter(eventId);
            suspected.remove(eventId);
            log.warn("쿠폰 자리 누수를 찾아 카운터를 DB 기준으로 재동기화: couponEventId={}, redisRemaining={}, dbRemaining={}",
                    eventId, redisRemaining, dbRemaining);
        }
    }

    private record Observation(long redisRemaining, int dbIssuedCount) {
    }
}
