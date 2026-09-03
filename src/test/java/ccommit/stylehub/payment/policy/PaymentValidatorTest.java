package ccommit.stylehub.payment.policy;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.enums.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * PaymentValidator의 결제 승인/취소 가능 여부, 금액 위변조, 배송 상태별 취소·환불 기한
 * 검증 로직을 검증하는 단위테스트이다. 외부 의존성이 없어 Mock 없이 실제 객체로 검증한다.
 * </p>
 */
class PaymentValidatorTest {

    private final PaymentValidator validator = new PaymentValidator();

    private Order orderWithStatus(OrderStatus status, LocalDateTime updatedAt) {
        Order order = Order.builder().orderStatus(status).build();
        ReflectionTestUtils.setField(order, "updatedAt", updatedAt);
        return order;
    }

    private Payment paymentWith(PaymentStatus status, Order order, int requestedAmount, int balanceAmount) {
        Payment payment = Payment.create(order, "key", "주문 결제", requestedAmount, requestedAmount, balanceAmount);
        ReflectionTestUtils.setField(payment, "status", status);
        return payment;
    }

    @Nested
    @DisplayName("validateApprovable")
    class ValidateApprovable {

        @ParameterizedTest
        @DisplayName("READY 또는 IN_PROGRESS면 예외가 발생하지 않는다")
        @EnumSource(value = PaymentStatus.class, names = {"READY", "IN_PROGRESS"})
        void 승인가능_상태면_통과한다(PaymentStatus status) {
            // given
            Payment payment = paymentWith(status, orderWithStatus(OrderStatus.PENDING, LocalDateTime.now()), 10000, 10000);

            // when & then
            assertThatCode(() -> validator.validateApprovable(payment)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("이미 처리된 결제면 PAYMENT_ALREADY_PROCESSED 예외가 발생한다")
        void 이미_처리된_결제면_예외() {
            // given
            Payment payment = paymentWith(PaymentStatus.DONE, orderWithStatus(OrderStatus.PAID, LocalDateTime.now()), 10000, 10000);

            // when & then
            assertThatThrownBy(() -> validator.validateApprovable(payment))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
    }

    @Nested
    @DisplayName("validateAmount")
    class ValidateAmount {

        @Test
        @DisplayName("요청 금액과 일치하면 예외가 발생하지 않는다")
        void 금액이_일치하면_통과한다() {
            // given
            Payment payment = paymentWith(PaymentStatus.READY, orderWithStatus(OrderStatus.PENDING, LocalDateTime.now()), 10000, 10000);

            // when & then
            assertThatCode(() -> validator.validateAmount(payment, 10000)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("요청 금액과 다르면 PAYMENT_AMOUNT_MISMATCH 예외가 발생한다")
        void 금액이_다르면_예외() {
            // given
            Payment payment = paymentWith(PaymentStatus.READY, orderWithStatus(OrderStatus.PENDING, LocalDateTime.now()), 10000, 10000);

            // when & then
            assertThatThrownBy(() -> validator.validateAmount(payment, 9999))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
    }

    @Nested
    @DisplayName("validateCancel")
    class ValidateCancel {

        @Test
        @DisplayName("배송 전 상태이고 DONE 결제를 전액 취소하면 예외가 발생하지 않는다")
        void 배송전_전액취소는_통과한다() {
            // given
            Payment payment = paymentWith(PaymentStatus.DONE, orderWithStatus(OrderStatus.PREPARING, LocalDateTime.now()), 10000, 10000);

            // when & then
            assertThatCode(() -> validator.validateCancel(payment, null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("배송 중이면 CANCEL_NOT_ALLOWED_SHIPPING 예외가 발생한다")
        void 배송중이면_예외() {
            // given
            Payment payment = paymentWith(PaymentStatus.DONE, orderWithStatus(OrderStatus.SHIPPING, LocalDateTime.now()), 10000, 10000);

            // when & then
            assertThatThrownBy(() -> validator.validateCancel(payment, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
        }

        @Test
        @DisplayName("배송 완료 후 7일 이내면 환불이 통과한다")
        void 배송완료_7일이내면_통과한다() {
            // given
            Order order = orderWithStatus(OrderStatus.DELIVERED, LocalDateTime.now().minusDays(6));
            Payment payment = paymentWith(PaymentStatus.DONE, order, 10000, 10000);

            // when & then
            assertThatCode(() -> validator.validateCancel(payment, null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("배송 완료 후 7일이 지나면 REFUND_PERIOD_EXPIRED 예외가 발생한다")
        void 배송완료_7일초과면_예외() {
            // given
            Order order = orderWithStatus(OrderStatus.DELIVERED, LocalDateTime.now().minusDays(8));
            Payment payment = paymentWith(PaymentStatus.DONE, order, 10000, 10000);

            // when & then
            assertThatThrownBy(() -> validator.validateCancel(payment, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.REFUND_PERIOD_EXPIRED);
        }

        @Test
        @DisplayName("결제 상태가 DONE/PARTIAL_CANCELED가 아니면 PAYMENT_ALREADY_PROCESSED 예외가 발생한다")
        void 취소불가능한_결제상태면_예외() {
            // given
            Payment payment = paymentWith(PaymentStatus.READY, orderWithStatus(OrderStatus.PENDING, LocalDateTime.now()), 10000, 10000);

            // when & then
            assertThatThrownBy(() -> validator.validateCancel(payment, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }

        @Test
        @DisplayName("PARTIAL_CANCELED 상태도 추가 취소가 가능하다")
        void PARTIAL_CANCELED_상태도_취소가능하다() {
            // given
            Payment payment = paymentWith(PaymentStatus.PARTIAL_CANCELED, orderWithStatus(OrderStatus.PREPARING, LocalDateTime.now()), 10000, 5000);

            // when & then
            assertThatCode(() -> validator.validateCancel(payment, 3000)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("부분 취소 금액이 잔액을 초과하면 INVALID_CANCEL_AMOUNT 예외가 발생한다")
        void 부분취소금액이_잔액초과면_예외() {
            // given
            Payment payment = paymentWith(PaymentStatus.DONE, orderWithStatus(OrderStatus.PREPARING, LocalDateTime.now()), 10000, 5000);

            // when & then
            assertThatThrownBy(() -> validator.validateCancel(payment, 5001))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CANCEL_AMOUNT);
        }

        @Test
        @DisplayName("부분 취소 금액이 잔액과 정확히 같으면 예외가 발생하지 않는다 (경계값)")
        void 부분취소금액이_잔액과_같으면_통과한다() {
            // given
            Payment payment = paymentWith(PaymentStatus.DONE, orderWithStatus(OrderStatus.PREPARING, LocalDateTime.now()), 10000, 5000);

            // when & then
            assertThatCode(() -> validator.validateCancel(payment, 5000)).doesNotThrowAnyException();
        }
    }
}
