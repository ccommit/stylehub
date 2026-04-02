package ccommit.stylehub.order.entity;

import ccommit.stylehub.common.entity.BaseEntity;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.enums.DeliveryStatus;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/03/27 by WonJin - feat: 주문 상태 변경 메서드 추가, 와일드카드 import 수정
 *
 * <p>
 * 사용자의 주문 정보를 관리한다.
 * 주문 상태와 배송 상태를 독립적으로 추적한다.
 * </p>
 */
@Entity
@Table(name = "orders")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    // TODO: 토스페이먼츠 결제 연동 시 PG사 주문번호로 활용
    @Column(name = "pg_order_id", nullable = false, unique = true, length = 64)
    private String pgOrderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "address_id", nullable = false)
    private Address address;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false)
    private OrderStatus orderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status")
    private DeliveryStatus deliveryStatus;

    @Column(name = "discount_amount", nullable = false)
    @Builder.Default
    private Integer discountAmount = 0;

    @Column(name = "used_point", nullable = false)
    @Builder.Default
    private Integer usedPoint = 0;

    @Column(name = "earned_point", nullable = false)
    @Builder.Default
    private Integer earnedPoint = 0;

    public static Order create(User user, Address address) {
        return Order.builder()
                .pgOrderId(generatePgOrderId())
                .user(user)
                .address(address)
                .orderStatus(OrderStatus.PENDING)
                .build();
    }

    private static String generatePgOrderId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return "ORD-" + date + "-" + uuid;
    }

    public int calculateFinalAmount(int totalAmount) {
        return totalAmount - this.discountAmount - this.usedPoint;
    }

    public void cancel() {
        if (this.orderStatus != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        this.orderStatus = OrderStatus.CANCELLED;
    }
}
