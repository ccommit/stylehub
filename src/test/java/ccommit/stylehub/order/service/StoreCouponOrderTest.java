package ccommit.stylehub.order.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.entity.CouponEvent;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.enums.CouponStatus;
import ccommit.stylehub.coupon.enums.DiscountType;
import ccommit.stylehub.coupon.repository.CouponEventRepository;
import ccommit.stylehub.coupon.repository.UserCouponRepository;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.scheduler.OrderTimeoutScheduler;
import ccommit.stylehub.payment.repository.PaymentRepository;
import ccommit.stylehub.product.repository.ProductOptionRepository;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 스토어 쿠폰이 발행 스토어 상품 금액에만 할인되는지를 실제 DB 로 검증하는 통합 테스트이다.
 * 여러 스토어 상품이 섞인 주문에서 할인 기준 금액, 적용 불가 시 롤백(재고·쿠폰 상태 유지)을 확인한다.
 * </p>
 */
@SpringBootTest
class StoreCouponOrderTest {

    private static final int STOCK = 10;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private CouponEventRepository couponEventRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderDetailRepository orderDetailRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<Long> userCouponIds = new ArrayList<>();
    private final List<Long> couponEventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(OrderTimeoutScheduler.ORDER_TIMEOUT_KEY);
        paymentRepository.deleteAll();
        orderDetailRepository.deleteAll();
        orderRepository.deleteAll();
        userCouponIds.forEach(userCouponRepository::deleteById);
        couponEventIds.forEach(couponEventRepository::deleteById);
        userCouponIds.clear();
        couponEventIds.clear();
    }

    @Test
    @DisplayName("스토어 A 의 10% 쿠폰은 A·B 상품이 섞인 주문에서 A 상품 금액(10,000원)에만 할인된다")
    void storeCouponDiscountsOnlyOwnStoreItems() {
        // given
        OrderFixtureFactory.Fixture buyer = fixtureFactory.create(STOCK);
        OrderFixtureFactory.StoreOption storeA = fixtureFactory.createApprovedStoreOption(STOCK);
        OrderFixtureFactory.StoreOption storeB = fixtureFactory.createApprovedStoreOption(STOCK);
        Long userCouponId = issueStoreCoupon(storeA.storeId(), buyer.userId(), DiscountType.RATE, 10);

        // when — 상품 가격은 모두 10,000원
        OrderResponse placed = orderService.placeOrder(buyer.userId(), new OrderCreateRequest(
                buyer.addressId(),
                List.of(new OrderDetailRequest(storeA.optionId(), 1), new OrderDetailRequest(storeB.optionId(), 1)),
                userCouponId
        ));

        // then
        assertThat(placed.totalAmount()).isEqualTo(20_000);
        assertThat(placed.discountAmount()).isEqualTo(1_000);
        assertThat(placed.finalAmount()).isEqualTo(19_000);
        assertThat(userCouponRepository.findById(userCouponId).orElseThrow().getStatus()).isEqualTo(CouponStatus.USED);
    }

    @Test
    @DisplayName("발행 스토어 상품이 없는 주문에 스토어 쿠폰을 쓰면 거절되고 재고 차감과 쿠폰 사용이 모두 롤백된다")
    void rejectsStoreCouponWithoutOwnItems() {
        // given
        OrderFixtureFactory.Fixture buyer = fixtureFactory.create(STOCK);
        OrderFixtureFactory.StoreOption storeA = fixtureFactory.createApprovedStoreOption(STOCK);
        OrderFixtureFactory.StoreOption storeB = fixtureFactory.createApprovedStoreOption(STOCK);
        Long userCouponId = issueStoreCoupon(storeA.storeId(), buyer.userId(), DiscountType.FIXED, 3000);

        // when & then
        assertThatThrownBy(() -> orderService.placeOrder(buyer.userId(), new OrderCreateRequest(
                buyer.addressId(), List.of(new OrderDetailRequest(storeB.optionId(), 1)), userCouponId)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.COUPON_NOT_APPLICABLE);

        assertThat(productOptionRepository.findById(storeB.optionId()).orElseThrow().getStockQuantity()).isEqualTo(STOCK);
        assertThat(userCouponRepository.findById(userCouponId).orElseThrow().getStatus()).isEqualTo(CouponStatus.UNUSED);
    }

    private Long issueStoreCoupon(Long storeId, Long buyerId, DiscountType type, int value) {
        LocalDateTime now = LocalDateTime.now();
        CouponEvent event = couponEventRepository.save(CouponEvent.create(
                userRepository.findById(storeId).orElseThrow(), "스토어쿠폰", type, value, 0, 100,
                now.minusMinutes(1), now.plusDays(1)));
        couponEventIds.add(event.getCouponEventId());
        UserCoupon userCoupon = userCouponRepository.save(
                UserCoupon.create(userRepository.findById(buyerId).orElseThrow(), event));
        userCouponIds.add(userCoupon.getUserCouponId());
        return userCoupon.getUserCouponId();
    }
}
