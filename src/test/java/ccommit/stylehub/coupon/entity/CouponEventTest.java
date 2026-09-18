package ccommit.stylehub.coupon.entity;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 쿠폰 유형별 할인 기준 금액 선택 규칙을 검증하는 단위 테스트이다.
 * 스토어 쿠폰이 다른 스토어 상품 금액까지 할인 기준에 넣지 않는지를 고정한다.
 * </p>
 */
class CouponEventTest {

    private static final Long STORE_A = 1L;
    private static final Long STORE_B = 2L;

    private final LocalDateTime start = LocalDateTime.now().minusMinutes(1);

    @Test
    @DisplayName("플랫폼 쿠폰은 주문 전체 금액을 할인 기준으로 삼는다")
    void platformCouponUsesTotal() {
        CouponEvent platform = CouponEvent.createPlatform("플랫폼", DiscountType.RATE, 10, 0, 100, start, start.plusDays(1));

        assertThat(platform.discountBaseAmount(Map.of(STORE_A, 10000, STORE_B, 20000))).isEqualTo(30000);
    }

    @Test
    @DisplayName("스토어 쿠폰은 발행 스토어 상품 금액만 할인 기준으로 삼는다")
    void storeCouponUsesOwnStoreAmountOnly() {
        CouponEvent storeCoupon = CouponEvent.create(User.builder().userId(STORE_A).build(), "스토어A",
                DiscountType.RATE, 10, 0, 100, start, start.plusDays(1));

        int base = storeCoupon.discountBaseAmount(Map.of(STORE_A, 10000, STORE_B, 20000));

        assertThat(base).isEqualTo(10000);
        assertThat(storeCoupon.calculateDiscount(base)).isEqualTo(1000);
    }

    @Test
    @DisplayName("발행 스토어 상품이 없는 주문에 스토어 쿠폰을 쓰면 COUPON_NOT_APPLICABLE 로 거절한다")
    void storeCouponRequiresOwnStoreItems() {
        CouponEvent storeCoupon = CouponEvent.create(User.builder().userId(STORE_A).build(), "스토어A",
                DiscountType.FIXED, 3000, 0, 100, start, start.plusDays(1));

        assertThatThrownBy(() -> storeCoupon.discountBaseAmount(Map.of(STORE_B, 20000)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_NOT_APPLICABLE);
    }

    @Test
    @DisplayName("최소 주문 금액은 할인 기준 금액으로 판단한다 — 다른 스토어 상품으로 최소 금액을 채울 수 없다")
    void minOrderAmountUsesBaseAmount() {
        CouponEvent storeCoupon = CouponEvent.create(User.builder().userId(STORE_A).build(), "스토어A",
                DiscountType.FIXED, 3000, 20000, 100, start, start.plusDays(1));

        int base = storeCoupon.discountBaseAmount(Map.of(STORE_A, 10000, STORE_B, 50000));

        assertThatThrownBy(() -> storeCoupon.calculateDiscount(base))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MIN_ORDER_AMOUNT_NOT_MET);
    }
}
