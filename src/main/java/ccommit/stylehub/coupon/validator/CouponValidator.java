package ccommit.stylehub.coupon.validator;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.coupon.entity.UserCoupon;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/04/16
 * @modified 2026/09/17 by WonJin - fix: 기획서 규칙 반영 — 정률 상한 90%, 기간 최소 1일, 시작일 과거 불가(서버 도착 지연 1분 허용), 진행 중 이벤트는 시작일 변경 없이 수정 가능
 *
 * <p>
 * 쿠폰 이벤트의 생성/수정/발급 시점별 검증 규칙을 담당한다.
 * 기존 CouponService에 흩어져 있던 검증 로직을 통합하여 단일 책임을 갖도록 한다.
 * </p>
 */
@Component
public class CouponValidator {

    // 기획서: 정률 할인은 최대 90%
    static final int MAX_RATE_DISCOUNT = 90;

    // 기획서: 종료일은 시작일 기준 최소 1일 후
    static final Duration MIN_EVENT_PERIOD = Duration.ofDays(1);

    // 기획서: 시작일은 과거로 설정할 수 없다. 클라이언트가 "지금"을 보내면 서버 도착 시점엔 이미 과거이므로 1분까지 허용한다.
    static final Duration START_TIME_TOLERANCE = Duration.ofMinutes(1);

    public void validateIssuable(CouponEvent event) {
        if (!event.getActive()) {
            throw new BusinessException(ErrorCode.COUPON_NOT_ACTIVE);
        }
        if (event.isNotStarted()) {
            throw new BusinessException(ErrorCode.COUPON_NOT_STARTED);
        }
        if (event.isExpired()) {
            throw new BusinessException(ErrorCode.COUPON_EXPIRED);
        }
    }

    // UNUSED 상태 검증은 UserCoupon.markUsed()가 맡는다.
    public void validateUsable(UserCoupon userCoupon) {
        validateIssuable(userCoupon.getCouponEvent());
    }

    public void validateCreate(CouponEventCreateRequest request) {
        if (request.startedAt().isBefore(LocalDateTime.now().minus(START_TIME_TOLERANCE))) {
            throw new BusinessException(ErrorCode.INVALID_COUPON_PERIOD);
        }
        validatePeriod(request.startedAt(), request.expiredAt());
        if (request.discountType() == DiscountType.RATE && request.discountValue() > MAX_RATE_DISCOUNT) {
            throw new BusinessException(ErrorCode.INVALID_DISCOUNT_VALUE);
        }
    }

    // 시작일을 그대로 두면 이미 시작된 이벤트도 수량·기간을 수정할 수 있도록, 시작일을 바꿀 때만 과거 여부를 검사한다.
    public void validateUpdate(CouponEvent event, CouponEventUpdateRequest request) {
        validatePeriod(request.startedAt(), request.expiredAt());
        boolean startChanged = !request.startedAt().isEqual(event.getStartedAt());
        if (startChanged && request.startedAt().isBefore(LocalDateTime.now().minus(START_TIME_TOLERANCE))) {
            throw new BusinessException(ErrorCode.INVALID_COUPON_PERIOD);
        }
        if (request.issueCount() < event.getIssuedCount()) {
            throw new BusinessException(ErrorCode.INVALID_DISCOUNT_VALUE);
        }
    }

    private void validatePeriod(LocalDateTime startedAt, LocalDateTime expiredAt) {
        if (expiredAt.isBefore(startedAt.plus(MIN_EVENT_PERIOD))) {
            throw new BusinessException(ErrorCode.INVALID_COUPON_PERIOD);
        }
    }
}
