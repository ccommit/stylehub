package ccommit.stylehub.coupon.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.CouponUsageResult;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.dto.response.CouponEventResponse;
import ccommit.stylehub.coupon.dto.response.UserCouponResponse;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.event.CouponIssuedEvent;
import ccommit.stylehub.coupon.port.CouponPort;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.UserCouponRepository;
import ccommit.stylehub.coupon.validator.CouponValidator;
import ccommit.stylehub.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/04/09
 * @modified 2026/04/16 by WonJin - refactor: 검증 로직을 CouponValidator로 분리
 * @modified 2026/04/22 by WonJin - refactor: UserPort 의존 제거, 권한 검증·User 조회는 CouponApplicationService로 이관 (도메인 서비스는 자기 도메인만 알도록 분리)
 * @modified 2026/05/06 by WonJin - perf: 선착순 발급 동시성 메커니즘 진화 — 비관적 락 → @DistributedLock (SETNX 폴링, 측정 결과 더 나쁨) → Redis DECR + Lua atomic 채택 (락 자체 제거, 정합성 + 처리량 모두 우월). UserCoupon INSERT 는 CouponIssu
 * @modified 2026/09/17 by WonJin - fix: 쿠폰 사용 시 할인 기준 금액을 쿠폰 유형별로 선택 (스토어 쿠폰은 발행 스토어 상품 금액만)
 *
 * <p>
 * 쿠폰 이벤트 생성과 선착순 쿠폰 발급을 담당하는 순수 도메인 서비스이다.
 * 선착순 발급은 Redis DECR + Lua atomic 으로 카운터 차감 + 중복 검증을 atomic 처리하고,
 * UserCoupon DB 저장은 CouponIssuedEvent 비동기 listener 가 담당한다.
 * 스토어 소유권 검증, User 조회 같은 Application 관심사는 CouponApplicationService에서 처리한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class CouponService implements CouponPort {

    private final CouponEventRepository couponEventRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponValidator couponValidator;
    private final StringRedisTemplate stringRedisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    // 카운터 차감과 중복 발급 검증을 Redis 단일 atomic 작업으로 묶어 락 없이 정합성을 보장한다.
    // 반환값: 0 이상은 남은 수량, -1은 SOLD_OUT, -2는 ALREADY_ISSUED
    private static final RedisScript<Long> ISSUE_COUPON_SCRIPT = new DefaultRedisScript<>(
            "local counterKey = KEYS[1]\n" +
            "local issuedSetKey = KEYS[2]\n" +
            "local userId = ARGV[1]\n" +
            "local maxCount = tonumber(ARGV[2])\n" +
            "\n" +
            "if redis.call('SISMEMBER', issuedSetKey, userId) == 1 then\n" +
            "    return -2\n" +
            "end\n" +
            "\n" +
            "if redis.call('EXISTS', counterKey) == 0 then\n" +
            "    redis.call('SET', counterKey, maxCount)\n" +
            "end\n" +
            "\n" +
            "local remaining = redis.call('DECR', counterKey)\n" +
            "if remaining < 0 then\n" +
            "    redis.call('INCR', counterKey)\n" +
            "    return -1\n" +
            "end\n" +
            "\n" +
            "redis.call('SADD', issuedSetKey, userId)\n" +
            "return remaining\n",
            Long.class
    );

    // 스토어 소유권 검증은 상위 계층에서 끝난 상태라고 가정한다.
    @Transactional
    public CouponEventResponse createStoreCouponEvent(User storeOwner, CouponEventCreateRequest request) {
        couponValidator.validateCreate(request);

        CouponEvent event = couponEventRepository.save(CouponEvent.create(
                storeOwner, request.name(), request.discountType(), request.discountValue(),
                request.minOrderAmount(), request.issueCount(), request.startedAt(), request.expiredAt()
        ));

        return CouponEventResponse.from(event);
    }

    @Transactional
    public CouponEventResponse createPlatformCouponEvent(CouponEventCreateRequest request) {
        couponValidator.validateCreate(request);

        CouponEvent event = couponEventRepository.save(CouponEvent.createPlatform(
                request.name(), request.discountType(), request.discountValue(),
                request.minOrderAmount(), request.issueCount(), request.startedAt(), request.expiredAt()
        ));

        return CouponEventResponse.from(event);
    }

    // 같은 쿠폰의 동시 사용을 막기 위해 UserCoupon을 비관적 락으로 읽는다.
    @Override
    @Transactional
    public CouponUsageResult useUserCoupon(Long userId, Long userCouponId, Map<Long, Integer> amountByStore) {
        UserCoupon userCoupon = userCouponRepository.findByIdWithLock(userCouponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_COUPON_NOT_FOUND));

        if (!userCoupon.getUser().getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_USER_COUPON);
        }

        couponValidator.validateUsable(userCoupon);
        CouponEvent event = userCoupon.getCouponEvent();
        int discount = event.calculateDiscount(event.discountBaseAmount(amountByStore));
        userCoupon.markUsed();

        return new CouponUsageResult(userCoupon, discount);
    }

    // 결제 실패·주문 취소 시 보상 트랜잭션에서 호출한다. 이미 UNUSED면 그대로 둔다(멱등).
    @Override
    @Transactional
    public void restoreUserCoupon(Long userCouponId) {
        UserCoupon userCoupon = userCouponRepository.findByIdWithLock(userCouponId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_COUPON_NOT_FOUND));
        userCoupon.markUnused();
    }

    // 발급 판단은 Redis Lua가 하고, UserCoupon 저장은 CouponIssuedEventListener가 비동기로 한다(eventual consistency).
    // 비동기 저장은 처리량 이득이 크지 않았지만 피크 흡수와 DB 자원 분리를 위해 유지한다.
    public void issueCoupon(User user, Long couponEventId) {
        CouponEvent event = couponEventRepository.findById(couponEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        couponValidator.validateIssuable(event);

        String counterKey = "coupon:counter:" + couponEventId;
        String issuedSetKey = "coupon:issued_users:" + couponEventId;

        Long result = stringRedisTemplate.execute(
                ISSUE_COUPON_SCRIPT,
                List.of(counterKey, issuedSetKey),
                user.getUserId().toString(),
                event.getIssueCount().toString()
        );

        if (result == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        if (result == -1L) {
            throw new BusinessException(ErrorCode.COUPON_SOLD_OUT);
        }
        if (result == -2L) {
            throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
        }

        // 비동기 저장 — 응답에서 DB INSERT 비용 분리
        eventPublisher.publishEvent(new CouponIssuedEvent(user.getUserId(), couponEventId));
    }

    @Transactional
    public CouponEventResponse updateCouponEvent(Long couponEventId, CouponEventUpdateRequest request) {
        CouponEvent event = couponEventRepository.findById(couponEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        couponValidator.validateUpdate(event, request);

        event.update(request.issueCount(), request.minOrderAmount(),
                request.startedAt(), request.expiredAt());

        return CouponEventResponse.from(event);
    }

    // 쿠폰 이벤트를 비활성화하여 발급을 중단한다.
    @Transactional
    public void deactivateCouponEvent(Long couponEventId) {
        CouponEvent event = couponEventRepository.findById(couponEventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COUPON_NOT_FOUND));

        event.deactivate();
    }

    private void checkDuplicateIssue(Long userId, Long couponEventId) {
        if (userCouponRepository.existsByUserUserIdAndCouponEventCouponEventId(userId, couponEventId)) {
            throw new BusinessException(ErrorCode.COUPON_ALREADY_ISSUED);
        }
    }

    // 내가 보유한 쿠폰 목록을 조회한다.
    @Transactional(readOnly = true)
    public List<UserCouponResponse> getMyCoupons(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        return userCouponRepository.findByUserIdWithCouponEvent(userId)
                .stream()
                .map(uc -> UserCouponResponse.from(uc, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CouponEventResponse> getActiveCouponEvents() {
        return couponEventRepository
                .findActiveCouponEvents(LocalDateTime.now())
                .stream()
                .map(CouponEventResponse::from)
                .toList();
    }

}
