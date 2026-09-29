package ccommit.stylehub.payment.service;

import ccommit.stylehub.payment.entity.PaymentRefundFailure;
import ccommit.stylehub.payment.repository.PaymentRefundFailureRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 자동 환불 실패를 기록하고 실패 횟수를 메트릭(payment_orphan_refund_failures_total)으로 내보낸다.
 * 이 메트릭이 늘면 Grafana 알림으로 사람이 알 수 있게 한다.
 * </p>
 */
@Component
public class PaymentRefundFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(PaymentRefundFailureRecorder.class);

    private final PaymentRefundFailureRepository refundFailureRepository;
    private final TransactionTemplate transactionTemplate;
    private final Counter failureCounter;

    public PaymentRefundFailureRecorder(PaymentRefundFailureRepository refundFailureRepository,
                                        TransactionTemplate transactionTemplate,
                                        MeterRegistry meterRegistry) {
        this.refundFailureRepository = refundFailureRepository;
        this.transactionTemplate = transactionTemplate;
        this.failureCounter = Counter.builder("payment.orphan.refund.failures")
                .description("만료·취소된 주문의 승인 건 자동 환불 실패 횟수")
                .register(meterRegistry);
    }

    // 기록마저 실패하면 환불 대상이 로그에만 남으므로 ERROR 로 남기고, 호출한 흐름은 막지 않는다.
    public void record(String paymentKey, String pgOrderId, RuntimeException cause) {
        failureCounter.increment();
        try {
            transactionTemplate.executeWithoutResult(status -> refundFailureRepository.findByPaymentKey(paymentKey)
                    .ifPresentOrElse(
                            failure -> failure.recordFailedAttempt(cause.getMessage()),
                            () -> refundFailureRepository.save(PaymentRefundFailure.of(paymentKey, pgOrderId, cause.getMessage()))
                    ));
        } catch (RuntimeException e) {
            log.error("[수동 환불 필요] 자동 환불 실패 기록 저장 실패: pgOrderId={}, paymentKey={}", pgOrderId, paymentKey, e);
        }
    }
}
