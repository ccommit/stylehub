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
 * <br>- DB INSERT 는 백그라운드 스레드에서 처리 → 응답 경로에서 분리
 *
 * <p><strong>측정 결과 — 처리량은 향상되지 않았다.</strong> 동기 저장 640 RPS 에서 비동기 632 RPS 로
 * 사실상 변화가 없었다. "비동기로 빼면 1,500~3,000 RPS" 라는 가설은 반박됐다.
 * 응답 시간을 분해해보니 DB INSERT 는 전체의 30~50 % 수준이었고, 실제 천장은 부하 클라이언트의
 * 대기 시간 설정과 단일 머신의 CPU 경합이었다.
 *
 * <p>그럼에도 이 구조를 유지하는 이유는 처리량이 아니라 피크 폭주 흡수와 DB 자원 분리, 그리고
 * 향후 메시지 큐 전환의 시작점이라는 판단 때문이다. 다만 이 값들은 단일 머신 측정 환경에서
 * 정량 입증하지 못했다.
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
