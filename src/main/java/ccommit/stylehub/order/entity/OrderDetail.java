package ccommit.stylehub.order.entity;

import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.product.entity.ProductOption;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 19:00 by WonJin - refactor: 모든 엔티티 클래스의 JPA 와일드카드 import를 명시적 import로 교체
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/04/08 by WonJin - refactor: OrderItem → OrderDetail 클래스명 변경, 테이블명 order_details로 변경
 *
 * <p>
 * 주문에 포함된 개별 상품 항목을 관리한다.
 * ProductOption을 직접 참조하여 옵션 단위 주문을 지원한다.
 * </p>
 */

@Entity
@Table(name = "order_details")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_detail_id")
    private Long orderDetailId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_option_id", nullable = false)
    private ProductOption productOption;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_coupon_id")
    private UserCoupon userCoupon;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false)
    private Integer unitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public int getTotalPrice() {
        return unitPrice * quantity;
    }

    /**
     * 주문 생성 *후* 사용된 쿠폰을 연결한다 (placeOrder 의 쿠폰 적용 단계에서 호출).
     * cancelOrder 의 보상 트랜잭션 시 어떤 UserCoupon 을 복구해야 하는지 추적용.
     */
    public void attachCoupon(UserCoupon userCoupon) {
        this.userCoupon = userCoupon;
    }

    public static OrderDetail create(ProductOption productOption, Order order,
                                     Integer quantity, Integer unitPrice,
                                     UserCoupon userCoupon) {
        return OrderDetail.builder()
                .productOption(productOption)
                .order(order)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .userCoupon(userCoupon)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
