package ccommit.stylehub.coupon.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/04/09
 * @modified 2026/09/17 by WonJin - fix: 시작일 @Future 제거 — 이미 시작된 이벤트도 시작일을 유지한 채 수량·기간을 수정할 수 있도록 CouponValidator 에서 검증
 *
 * <p>
 * 쿠폰 이벤트 수정 요청 DTO이다.
 * 기간, 수량, 최소 주문 금액을 변경할 수 있다.
 * </p>
 */
public record CouponEventUpdateRequest(

        @NotNull(message = "발행 수량은 필수입니다")
        @Positive(message = "발행 수량은 0보다 커야 합니다")
        Integer issueCount,

        @NotNull(message = "최소 주문 금액은 필수입니다")
        @PositiveOrZero(message = "최소 주문 금액은 0 이상이어야 합니다")
        Integer minOrderAmount,

        @NotNull(message = "시작일은 필수입니다")
        LocalDateTime startedAt,

        @NotNull(message = "만료일은 필수입니다")
        @Future(message = "만료일은 현재보다 이후여야 합니다")
        LocalDateTime expiredAt
) {}
