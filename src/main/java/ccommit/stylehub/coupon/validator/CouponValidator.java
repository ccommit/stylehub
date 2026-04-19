package ccommit.stylehub.coupon.validator;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.dto.request.CouponEventUpdateRequest;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.enums.DiscountType;
import org.springframework.stereotype.Component;

/**
 * @author WonJin Bae
 * @created 2026/04/16
 *
 * <p>
 * 쿠폰 이벤트의 생성/수정/발급 시점별 검증 규칙을 담당한다.
 * 기존 CouponService에 흩어져 있던 검증 로직을 통합하여 단일 책임을 갖도록 한다.
 * </p>
 */
@Component
public class CouponValidator {

    /**
     * 쿠폰 발급 시점 검증.
     * - 비활성 상태가 아닐 것
     * - 시작 전이 아닐 것
     * - 만료되지 않았을 것
     */
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

    /**
     * 쿠폰 이벤트 생성 시점 검증.
     * - 유효기간이 올바를 것 (시작일 <= 만료일)
     * - 할인 유형이 RATE인 경우 할인 값이 100% 이하일 것
     */
    public void validateCreate(CouponEventCreateRequest request) {
        if (request.startedAt().isAfter(request.expiredAt())) {
            throw new BusinessException(ErrorCode.INVALID_COUPON_PERIOD);
        }
        if (request.discountType() == DiscountType.RATE && request.discountValue() > 100) {
            throw new BusinessException(ErrorCode.INVALID_DISCOUNT_VALUE);
        }
    }

    /**
     * 쿠폰 이벤트 수정 시점 검증.
     * - 유효기간이 올바를 것
     * - 이미 발급된 수량보다 적은 수량으로 변경하지 않을 것
     */
    public void validateUpdate(CouponEvent event, CouponEventUpdateRequest request) {
        if (request.startedAt().isAfter(request.expiredAt())) {
            throw new BusinessException(ErrorCode.INVALID_COUPON_PERIOD);
        }
        if (request.issueCount() < event.getIssuedCount()) {
            throw new BusinessException(ErrorCode.INVALID_DISCOUNT_VALUE);
        }
    }
}
