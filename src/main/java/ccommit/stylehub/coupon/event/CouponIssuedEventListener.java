package ccommit.stylehub.coupon.event;

import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.UserCouponRepository;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.port.UserPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author WonJin Bae
 * @created 2026/05/06
 *
 * <p>
 * CouponIssuedEvent 를 받아 UserCoupon 을 *비동기* 로 DB 에 INSERT 한다.
 *
 * <p>설계 의도:
 * <br>- Redis DECR 로 *발급 자체* 는 즉시 확정 (atomic) → 응답 즉시 반환
 * <br>- DB INSERT 는 백그라운드 스레드에서 처리 → 응답시간에서 분리
 * <br>- 결과: 응답 시간 short, 처리량 (RPS) 큰 폭 향상
 *
 * <p>실패 시: ERROR 로그만 남김. Redis 가 source of truth 라 정합성은 유지됨.
 * 운영 환경에서는 데드레터 큐 / 재시도 메커니즘 추가 권장.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class CouponIssuedEventListener {

    private static final Logger log = LoggerFactory.getLogger(CouponIssuedEventListener.class);

    private final UserCouponRepository userCouponRepository;
    private final CouponEventRepository couponEventRepository;
    private final UserPort userPort;

    @Async("couponInsertExecutor")
    @EventListener
    @Transactional
    public void handleCouponIssued(CouponIssuedEvent event) {
        try {
            User user = userPort.findUserById(event.userId());
            CouponEvent couponEvent = couponEventRepository.findById(event.couponEventId())
                    .orElseThrow(() -> new IllegalStateException("쿠폰 이벤트 미존재: " + event.couponEventId()));

            userCouponRepository.save(UserCoupon.create(user, couponEvent));
        } catch (Exception e) {
            // Redis 는 이미 발급 확정 — DB INSERT 만 실패. 로그 후 모니터링/재시도 영역.
            log.error("UserCoupon 비동기 INSERT 실패 — userId={} couponEventId={}",
                    event.userId(), event.couponEventId(), e);
        }
    }
}
