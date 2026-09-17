package ccommit.stylehub.payment.entity;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.payment.enums.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * Payment 엔티티의 상태 전이 불변식을 검증하는 단위 테스트이다.
 * 서비스가 상태 확인을 빠뜨려도 승인된 결제가 실패 처리되지 않는지를 엔티티 수준에서 고정한다.
 * </p>
 */
class PaymentTest {

    private Payment paymentWithStatus(PaymentStatus status) {
        Payment payment = Payment.create(Order.builder().build(), "", "주문 결제", 10000, 10000, 10000);
        ReflectionTestUtils.setField(payment, "status", status);
        return payment;
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = PaymentStatus.class, names = {"READY", "IN_PROGRESS"})
    @DisplayName("승인 대기 상태의 결제는 실패 처리할 수 있다")
    void abortsAwaitingPayment(PaymentStatus status) {
        Payment payment = paymentWithStatus(status);

        payment.abort();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ABORTED);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @EnumSource(value = PaymentStatus.class, names = {"READY", "IN_PROGRESS"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("승인 대기 상태가 아닌 결제는 실패 처리하면 PAYMENT_ALREADY_PROCESSED 예외가 발생하고 상태가 유지된다")
    void rejectsAbortOfProcessedPayment(PaymentStatus status) {
        Payment payment = paymentWithStatus(status);

        assertThatThrownBy(payment::abort)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        assertThat(payment.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("승인 대기 여부는 READY 와 IN_PROGRESS 에서만 참이다")
    void awaitingApprovalOnlyForReadyAndInProgress() {
        assertThat(paymentWithStatus(PaymentStatus.READY).isAwaitingApproval()).isTrue();
        assertThat(paymentWithStatus(PaymentStatus.IN_PROGRESS).isAwaitingApproval()).isTrue();
        assertThat(paymentWithStatus(PaymentStatus.DONE).isAwaitingApproval()).isFalse();
        assertThat(paymentWithStatus(PaymentStatus.ABORTED).isAwaitingApproval()).isFalse();
    }
}
