package ccommit.stylehub.payment.entity;

import ccommit.stylehub.common.entity.BaseEntity;
import ccommit.stylehub.common.util.HashUtils;
import ccommit.stylehub.payment.enums.RefundFailureStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 만료·취소된 주문에 들어온 PG 승인을 자동 환불하지 못한 건을 남긴다.
 * 로그 한 줄로 끝나면 돈만 빠진 결제를 놓치므로, 재시도 스케줄러가 이 기록으로 환불을 다시 요청한다.
 * </p>
 */
@Entity
@Table(name = "payment_refund_failures",
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_refund_failures_payment_key", columnNames = "payment_key"),
        indexes = @Index(name = "idx_payment_refund_failures_status", columnList = "status, refund_failure_id"))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRefundFailure extends BaseEntity {

    public static final String REFUND_REASON = "결제 대기 시간이 지나 취소된 주문의 승인 건 자동 환불";

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_failure_id")
    private Long refundFailureId;

    @Column(name = "payment_key", nullable = false, length = 200)
    private String paymentKey;

    @Column(name = "pg_order_id", nullable = false, length = 64)
    private String pgOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RefundFailureStatus status = RefundFailureStatus.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    // 같은 결제의 환불은 몇 번을 다시 요청해도 같은 키라, 앞선 요청이 PG 에서 처리됐으면 PG 가 첫 결과를 돌려준다.
    public static String refundIdempotencyKey(String paymentKey) {
        return HashUtils.sha256Hex("orphan-refund|" + paymentKey);
    }

    public static PaymentRefundFailure of(String paymentKey, String pgOrderId, String error) {
        return PaymentRefundFailure.builder()
                .paymentKey(paymentKey)
                .pgOrderId(pgOrderId)
                .attemptCount(1)
                .lastError(truncate(error))
                .build();
    }

    public void recordFailedAttempt(String error) {
        this.attemptCount++;
        this.lastError = truncate(error);
    }

    public void resolve() {
        this.status = RefundFailureStatus.RESOLVED;
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
