package ccommit.stylehub.common.schema;

import ccommit.stylehub.coupon.dto.request.CouponEventCreateRequest;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.coupon.service.CouponService;
import ccommit.stylehub.support.OrderFixtureFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 조건부 UPDATE 를 거치지 않는 변경도 재고·포인트 음수와 발급 한도 초과를 DB 제약이 막는지 실제 MySQL 로 검증한다.
 * </p>
 */
@SpringBootTest
class CheckConstraintTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private CouponService couponService;

    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> couponEventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        couponEventIds.forEach(id -> jdbcTemplate.update("DELETE FROM coupon_events WHERE coupon_event_id = ?", id));
        fixtureFactory.deleteByIds(List.of(), productIds, userIds);
    }

    @Test
    @DisplayName("재고를 음수로 바꾸는 UPDATE 는 DB 가 거절한다")
    void rejectsNegativeStock() {
        OrderFixtureFactory.StoreProduct product = fixtureFactory.createStoreProduct(1);
        productIds.add(product.productId());
        userIds.add(product.storeId());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE products_options SET stock_quantity = stock_quantity - 2 WHERE product_option_id = ?", product.optionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_products_options_stock_nonnegative");
    }

    @Test
    @DisplayName("포인트 잔액을 음수로 바꾸는 UPDATE 는 DB 가 거절한다")
    void rejectsNegativePoint() {
        OrderFixtureFactory.Buyer buyer = fixtureFactory.createBuyer();
        userIds.add(buyer.userId());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE users SET point_balance = -1 WHERE user_id = ?", buyer.userId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_users_point_nonnegative");
    }

    @Test
    @DisplayName("발급 수를 발급 한도보다 크게 바꾸는 UPDATE 는 DB 가 거절한다")
    void rejectsIssuedCountOverLimit() {
        LocalDateTime now = LocalDateTime.now();
        Long eventId = couponService.createPlatformCouponEvent(new CouponEventCreateRequest(
                "제약테스트", DiscountType.FIXED, 1000, 0, 1, now.minusSeconds(10), now.plusDays(1)
        )).couponEventId();
        couponEventIds.add(eventId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE coupon_events SET issued_count = issue_count + 1 WHERE coupon_event_id = ?", eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_coupon_events_issued_within_limit");
    }
}
