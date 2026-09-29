package ccommit.stylehub.payment.scheduler;

import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.entity.PaymentRefundFailure;
import ccommit.stylehub.payment.enums.RefundFailureStatus;
import ccommit.stylehub.payment.repository.PaymentRefundFailureRepository;
import ccommit.stylehub.payment.service.PaymentRefundFailureRecorder;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 자동 환불에 실패한 승인 건의 환불을 주기적으로 다시 요청한다.
 * 같은 결제는 항상 같은 멱등 키로 요청하므로 여러 서버가 함께 돌거나 앞선 요청이 PG 에서 처리됐어도 이중 환불되지 않는다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class PaymentRefundRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundRetryScheduler.class);

    private static final String PG_TYPE = "TOSS";
    private static final int BATCH_SIZE = 50;

    private final PaymentRefundFailureRepository refundFailureRepository;
    private final PaymentRefundFailureRecorder refundFailureRecorder;
    private final PaymentClientFactory paymentClientFactory;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void retryPendingRefunds() {
        List<PaymentRefundFailure> pending = refundFailureRepository.findByStatusOrderByRefundFailureIdAsc(
                RefundFailureStatus.PENDING, PageRequest.of(0, BATCH_SIZE));

        for (PaymentRefundFailure failure : pending) {
            retry(failure.getRefundFailureId(), failure.getPaymentKey(), failure.getPgOrderId());
        }
    }

    // PG 호출은 트랜잭션 밖에서 해 응답을 기다리는 동안 커넥션을 쥐지 않는다.
    private void retry(Long refundFailureId, String paymentKey, String pgOrderId) {
        try {
            paymentClientFactory.getClient(PG_TYPE).cancelPayment(
                    paymentKey, PaymentRefundFailure.REFUND_REASON, null, PaymentRefundFailure.refundIdempotencyKey(paymentKey));
        } catch (RuntimeException e) {
            log.error("자동 환불 재시도 실패: pgOrderId={}, paymentKey={}", pgOrderId, paymentKey, e);
            refundFailureRecorder.record(paymentKey, pgOrderId, e);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> refundFailureRepository.findById(refundFailureId)
                .ifPresent(PaymentRefundFailure::resolve));
        log.warn("자동 환불 재시도 성공: pgOrderId={}", pgOrderId);
    }
}
