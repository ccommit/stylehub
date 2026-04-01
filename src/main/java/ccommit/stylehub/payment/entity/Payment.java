package ccommit.stylehub.payment.entity;

import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.payment.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 19:00 by WonJin - refactor: 모든 엔티티 클래스의 JPA 와일드카드 import를 명시적 import로 교체
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 주문에 대한 결제 정보를 관리한다.
 * Order와 1:1 관계로 PG사(토스페이먼츠) 연동 필드를 포함한다.
 * </p>
 */

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

    // 결제 실패 처리
    public void abort() {
        this.status = PaymentStatus.ABORTED;
        this.updatedAt = LocalDateTime.now();
    }

    // 결제 승인 완료 처리
    public void approve(Integer approvedAmount) {
        this.status = PaymentStatus.DONE;
        this.approvedAmount = approvedAmount;
        this.approvedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // 취소 처리 — cancelAmount가 null이면 전액 취소, 값이 있으면 부분 취소
    public void cancel(String reason, Integer cancelAmount) {
        if (cancelAmount == null) {
            this.status = PaymentStatus.CANCELED;
            this.cancelAmount = this.approvedAmount;
            this.balanceAmount = 0;
        } else {
            this.cancelAmount = (this.cancelAmount != null ? this.cancelAmount : 0) + cancelAmount;
            this.balanceAmount = this.balanceAmount - cancelAmount;
            this.status = (this.balanceAmount == 0) ? PaymentStatus.CANCELED : PaymentStatus.PARTIAL_CANCELED;
        }
        this.cancelReason = reason;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isFullyCanceled() {
        return this.status == PaymentStatus.CANCELED;
    }
}
