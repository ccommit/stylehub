package ccommit.stylehub.coupon.dto.request;

import ccommit.stylehub.coupon.enums.DiscountType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/04/09
 *
 * <p>
 * 쿠폰 이벤트 생성 요청 DTO이다.
 * </p>
 */
public record CouponEventCreateRequest(

        @NotBlank(message = "쿠폰 이벤트명은 필수입니다")
        @Size(max = 20, message = "쿠폰 이벤트명은 20자 이내여야 합니다")
        String name,

        @NotNull(message = "할인 유형은 필수입니다")
        DiscountType discountType,

        @NotNull(message = "할인 값은 필수입니다")
        @Positive(message = "할인 값은 0보다 커야 합니다")
        Integer discountValue,

        @NotNull(message = "최소 주문 금액은 필수입니다")
        @PositiveOrZero(message = "최소 주문 금액은 0 이상이어야 합니다")
        Integer minOrderAmount,

        @NotNull(message = "발행 수량은 필수입니다")
        @Positive(message = "발행 수량은 0보다 커야 합니다")
        Integer issueCount,

        @NotNull(message = "시작일은 필수입니다")
        @Future(message = "시작일은 현재보다 이후여야 합니다")
        LocalDateTime startedAt,

        @NotNull(message = "만료일은 필수입니다")
        @Future(message = "만료일은 현재보다 이후여야 합니다")
        LocalDateTime expiredAt
) {}
