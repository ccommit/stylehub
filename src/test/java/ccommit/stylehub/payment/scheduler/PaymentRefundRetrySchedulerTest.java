package ccommit.stylehub.payment.scheduler;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.payment.client.PaymentClient;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.entity.PaymentRefundFailure;
import ccommit.stylehub.payment.enums.RefundFailureStatus;
import ccommit.stylehub.payment.repository.PaymentRefundFailureRepository;
import ccommit.stylehub.payment.service.PaymentRefundFailureRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 자동 환불 실패 기록이 실제 DB 에 남고, 재시도 스케줄러가 같은 멱등 키로 환불을 다시 요청해 성공하면 기록을 닫는지 검증한다.
 * </p>
 */
@SpringBootTest
class PaymentRefundRetrySchedulerTest {

    private static final String PAYMENT_KEY = "pk-orphan-1";
    private static final String PG_ORDER_ID = "ORD-orphan-1";

    @MockitoBean
    private PaymentClientFactory paymentClientFactory;

    @Autowired
    private PaymentRefundRetryScheduler scheduler;

    @Autowired
    private PaymentRefundFailureRecorder recorder;

    @Autowired
    private PaymentRefundFailureRepository refundFailureRepository;

    private PaymentClient paymentClient;

    @BeforeEach
    void setUp() {
        paymentClient = mock(PaymentClient.class);
        given(paymentClientFactory.getClient(any())).willReturn(paymentClient);
    }

    @AfterEach
    void cleanUp() {
        refundFailureRepository.deleteAll();
    }

    @Test
    @DisplayName("재시도가 실패하면 기록을 유지하고 시도 횟수를 늘리며, 성공하면 기록을 닫고 다시 요청하지 않는다")
    void retriesUntilRefunded() {
        // given
        recorder.record(PAYMENT_KEY, PG_ORDER_ID, new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED));
        willThrow(new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED))
                .willDoNothing()
                .given(paymentClient).cancelPayment(any(), any(), any(), any());

        // when
        scheduler.retryPendingRefunds();
        PaymentRefundFailure afterFailedRetry = onlyRecord();
        scheduler.retryPendingRefunds();
        scheduler.retryPendingRefunds();

        // then
        assertThat(afterFailedRetry.getStatus()).isEqualTo(RefundFailureStatus.PENDING);
        assertThat(afterFailedRetry.getAttemptCount()).isEqualTo(2);
        assertThat(onlyRecord().getStatus()).isEqualTo(RefundFailureStatus.RESOLVED);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        then(paymentClient).should(times(2)).cancelPayment(eq(PAYMENT_KEY), any(), isNull(), keys.capture());
        assertThat(keys.getAllValues()).containsOnly(PaymentRefundFailure.refundIdempotencyKey(PAYMENT_KEY));
    }

    @Test
    @DisplayName("같은 결제의 환불 실패를 여러 번 기록해도 한 건으로 모이고 시도 횟수만 늘어난다")
    void mergesFailuresOfSamePayment() {
        // when
        recorder.record(PAYMENT_KEY, PG_ORDER_ID, new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED));
        recorder.record(PAYMENT_KEY, PG_ORDER_ID, new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED));

        // then
        assertThat(onlyRecord().getAttemptCount()).isEqualTo(2);
        then(paymentClient).should(never()).cancelPayment(any(), any(), any(), any());
    }

    private PaymentRefundFailure onlyRecord() {
        List<PaymentRefundFailure> records = refundFailureRepository.findAll();
        assertThat(records).hasSize(1);
        return records.get(0);
    }
}
