package ccommit.stylehub.payment.entity;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
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

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 19:00 by WonJin - refactor: 모든 엔티티 클래스의 JPA 와일드카드 import를 명시적 import로 교체
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/09/17 by WonJin - fix: 승인 대기 상태에서만 실패 처리(abort)되도록 불변식 추가
 * @modified 2026/09/17 by WonJin - fix: 승인 선점(IN_PROGRESS)·되돌림·만료 전이 추가, 승인은 대기 상태에서만 허용
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

    public boolean isAwaitingApproval() {
        return this.status == PaymentStatus.READY || this.status == PaymentStatus.IN_PROGRESS;
    }

    // 승인된 결제를 ABORTED로 덮으면 환불할 방법이 사라지므로 승인 전에만 허용한다
    public void abort() {
        if (!isAwaitingApproval()) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        this.status = PaymentStatus.ABORTED;
        this.updatedAt = LocalDateTime.now();
    }

    // IN_PROGRESS를 먼저 커밋해, PG 호출 동안 행 락 없이도 중복 승인·실패 콜백·만료 처리가 진행 중임을 알게 한다
    public void startApproval(String paymentKey) {
        if (this.status != PaymentStatus.READY) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        this.paymentKey = paymentKey;
        this.status = PaymentStatus.IN_PROGRESS;
        this.updatedAt = LocalDateTime.now();
    }

    // PG 가 승인을 명확히 거절한 경우 다시 시도할 수 있도록 선점을 되돌린다(IN_PROGRESS → READY).
    public void revertApproval() {
        if (this.status != PaymentStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        this.status = PaymentStatus.READY;
        this.updatedAt = LocalDateTime.now();
    }

    // 승인 요청이 PG 에서 처리 중일 수 있는 시간 안인지 확인한다. 이 시간이 지나면 응답이 유실된 것으로 본다.
    public boolean isApprovalInFlight(LocalDateTime now, Duration grace) {
        return this.status == PaymentStatus.IN_PROGRESS && this.updatedAt.isAfter(now.minus(grace));
    }

    // 결제 대기 시간이 지나 승인되지 않은 결제를 만료 처리한다. 이후 늦게 도착한 승인 요청은 거절된다.
    public void expire() {
        if (!isAwaitingApproval()) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        this.status = PaymentStatus.EXPIRED;
        this.updatedAt = LocalDateTime.now();
    }

    // 결제 승인 완료 처리 — paymentKey는 토스 인증 완료 후 전달받는다. 승인 대기 상태에서만 허용한다.
    public void approve(String paymentKey, Integer approvedAmount) {
        if (!isAwaitingApproval()) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        this.paymentKey = paymentKey;
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
