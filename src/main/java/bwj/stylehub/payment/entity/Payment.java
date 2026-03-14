package bwj.stylehub.payment.entity;

import bwj.stylehub.order.entity.Order;
import bwj.stylehub.payment.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "payment_key", nullable = false, length = 200)
    private String paymentKey;

    @Column(name = "order_name", nullable = false, length = 100)
    private String orderName;

    @Column(name = "requested_amount", nullable = false)
    private Integer requestedAmount;

    @Column(name = "total_amount", nullable = false)
    private Integer totalAmount;

    @Column(name = "approved_amount", nullable = false)
    private Integer approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.READY;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "cancel_amount")
    private Integer cancelAmount;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "balance_amount", nullable = false)
    private Integer balanceAmount;

    public static Payment create(Order order, String paymentKey, String orderName,
                                 Integer requestedAmount, Integer totalAmount, Integer balanceAmount) {
        LocalDateTime now = LocalDateTime.now();
        return Payment.builder()
                .order(order)
                .paymentKey(paymentKey)
                .orderName(orderName)
                .requestedAmount(requestedAmount)
                .totalAmount(totalAmount)
                .approvedAmount(0)
                .balanceAmount(balanceAmount)
                .requestedAt(now)
                .updatedAt(now)
                .build();
    }
}
