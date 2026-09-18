package ccommit.stylehub.order.entity;

import ccommit.stylehub.common.entity.BaseEntity;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
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
 * @modified 2026/04/02 by WonJin - feat: 배송 상태 전이 메서드 추가
 * @modified 2026/04/16 by WonJin - refactor: DeliveryStatus를 OrderStatus로 통합
 * @modified 2026/04/22 by WonJin - refactor: cancel/cancelPaid 통합 (내부 상태 PENDING/PAID 모두 허용) — 호출자가 상태를 알 필요 없게 함
 * @modified 2026/05/08 by WonJin - feat: applyDiscount 추가 (쿠폰 사용 주문 시 할인 금액 반영)
 * @modified 2026/09/17 by WonJin - fix: 결제 대기 여부 조회 추가 (만료 처리는 결제 대기 주문만 취소)
 * @modified 2026/09/17 by WonJin - fix: 취소를 결제 전(cancelUnpaid)·결제 후 환불(cancelPaid)로 나누고 결제 후 취소 허용 상태를 한 곳에서 정의, 미사용 startDelivery 제거
 * @modified 2026/09/17 by WonJin - fix: 주문번호 난수를 UUID 8자리(32비트)에서 전체 122비트로 확장 (대량 주문 시 유니크 충돌 방지)
 *
 * <p>
 * 사용자의 주문 정보를 관리한다.
 * OrderStatus 하나로 주문 및 배송 상태를 통합 추적한다.
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

    // 충돌하면 pg_order_id 유니크 제약 위반으로 주문 생성이 실패하므로 UUID 난수 전체(122비트)를 쓴다.
    // ORD-yyyyMMdd-32자리 16진수(45자)로 토스 orderId 규칙(영문·숫자·'-'·'_', 6~64자)과 컬럼 길이 64를 지킨다.
    private static String generatePgOrderId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String random = UUID.randomUUID().toString().replace("-", "");
        return "ORD-" + date + "-" + random;
    }

    public int calculateFinalAmount(int totalAmount) {
        return totalAmount - this.discountAmount - this.usedPoint;
    }

    public void applyDiscount(int discountAmount) {
        this.discountAmount = discountAmount;
    }

    // 결제 대기(PENDING) 주문인지 확인한다. 만료·결제 실패 처리는 이 상태의 주문만 취소한다.
    public boolean isAwaitingPayment() {
        return this.orderStatus == OrderStatus.PENDING;
    }

    // 결제 전 취소 — 결제 대기(PENDING) 주문만 가능하다. 결제 만료·실패 처리가 사용한다.
    public void cancelUnpaid() {
        if (!isAwaitingPayment()) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        this.orderStatus = OrderStatus.CANCELLED;
    }

    // 결제 검증과 주문 취소의 허용 상태가 다르면 PG 환불 뒤 주문 취소가 거절돼 어긋나므로 둘이 이 규칙을 함께 쓴다.
    // 배송 완료(DELIVERED) 주문의 환불 기한은 결제 검증기가 따로 확인한다.
    public boolean isCancelableAfterPayment() {
        return this.orderStatus == OrderStatus.PAID
                || this.orderStatus == OrderStatus.PREPARING
                || this.orderStatus == OrderStatus.DELIVERED;
    }

    // 결제 후 취소(환불) — 결제 완료·배송 준비·배송 완료 주문만 가능하다. 배송 중에는 취소할 수 없다.
    public void cancelPaid() {
        if (!isCancelableAfterPayment()) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        this.orderStatus = OrderStatus.CANCELLED;
    }

    // 주문 상태를 변경한다. 검증은 DeliveryValidator에서 처리.
    public void updateOrderStatus(OrderStatus newStatus) {
        this.orderStatus = newStatus;
    }

    // 결제 완료 처리 — PENDING → PAID. 배송 준비(PREPARING)는 스토어가 주문을 확인하고 배송 상태 API 로 전환한다.
    public void markPaid() {
        if (this.orderStatus != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }
        this.orderStatus = OrderStatus.PAID;
    }
}
