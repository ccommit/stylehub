package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.payment.client.PaymentClient;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.event.PaymentApprovedEvent;
import ccommit.stylehub.payment.policy.PaymentValidator;
import ccommit.stylehub.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/04/24
 *
 * <p>
 * PaymentService.confirmPayment 의 단위 테스트이다.
 * 정상 승인 / 미존재 결제 / 이미 처리된 결제 / 금액 불일치 / PG 호출 실패 경로를 검증한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentClientFactory paymentClientFactory;

    @Mock
    private PaymentValidator paymentValidator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private EntityManager em;

    @Mock
    private PaymentClient paymentClient;

    @InjectMocks
    private PaymentService paymentService;

    @Nested
    @DisplayName("confirmPayment (결제 승인)")
    class ConfirmPayment {

        @Test
        @DisplayName("정상 승인 시 PG 호출 → payment.approve → markPaid → 이벤트 발행이 순차 수행된다")
        void approvesPaymentSuccessfully() {
            // given
            String paymentKey = "pk-1";
            String pgOrderId = "ORD-20260424-abc";
            Integer tossAmount = 10_000;

            Order order = mock(Order.class);
            given(order.getOrderId()).willReturn(1L);

            Payment payment = mock(Payment.class);
            given(payment.getOrder()).willReturn(order);

            given(paymentRepository.findByOrderPgOrderId(pgOrderId))
                    .willReturn(Optional.of(payment));
            willDoNothing().given(paymentValidator).validateApprovable(payment);
            willDoNothing().given(paymentValidator).validateAmount(payment, tossAmount);
            given(paymentClientFactory.getClient("TOSS")).willReturn(paymentClient);
            willDoNothing().given(paymentClient).confirmPayment(paymentKey, pgOrderId, tossAmount);

            // when
            PaymentResponse response = paymentService.confirmPayment(paymentKey, pgOrderId, tossAmount);

            // then
            assertThat(response).isNotNull();
            then(paymentClient).should().confirmPayment(paymentKey, pgOrderId, tossAmount);
            then(payment).should().approve(paymentKey, tossAmount);
            then(order).should().markPaid();
            then(eventPublisher).should().publishEvent(any(PaymentApprovedEvent.class));
        }

        @Test
        @DisplayName("pgOrderId 에 해당하는 결제가 없으면 PAYMENT_NOT_FOUND 를 던진다")
        void throwsNotFound_whenPaymentMissing() {
            // given
            given(paymentRepository.findByOrderPgOrderId("ORD-X")).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> paymentService.confirmPayment("pk", "ORD-X", 10000))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_NOT_FOUND);
            then(paymentClientFactory).should(never()).getClient(any());
        }

        @Test
        @DisplayName("이미 처리된 결제라면 validateApprovable 단계에서 차단되고 PG 호출은 발생하지 않는다")
        void doesNotCallPg_whenPaymentAlreadyProcessed() {
            // given
            Payment payment = mock(Payment.class);
            given(paymentRepository.findByOrderPgOrderId("ORD-X")).willReturn(Optional.of(payment));
            willThrow(new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED))
                    .given(paymentValidator).validateApprovable(payment);

            // when / then
            assertThatThrownBy(() -> paymentService.confirmPayment("pk", "ORD-X", 10000))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_ALREADY_PROCESSED);
            then(paymentClientFactory).should(never()).getClient(any());
            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("결제 금액이 서버 레코드와 다르면 PAYMENT_AMOUNT_MISMATCH 를 던진다")
        void throwsAmountMismatch_whenAmountDiffers() {
            // given
            Payment payment = mock(Payment.class);
            given(paymentRepository.findByOrderPgOrderId("ORD-X")).willReturn(Optional.of(payment));
            willDoNothing().given(paymentValidator).validateApprovable(payment);
            willThrow(new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH))
                    .given(paymentValidator).validateAmount(payment, 9999);

            // when / then
            assertThatThrownBy(() -> paymentService.confirmPayment("pk", "ORD-X", 9999))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_AMOUNT_MISMATCH);
            then(paymentClientFactory).should(never()).getClient(any());
        }

        @Test
        @DisplayName("PG 호출이 실패하면 예외가 전파되고 DB 승인 처리는 발생하지 않는다")
        void propagatesPgFailure() {
            // given
            Payment payment = mock(Payment.class);
            given(paymentRepository.findByOrderPgOrderId("ORD-X")).willReturn(Optional.of(payment));
            willDoNothing().given(paymentValidator).validateApprovable(payment);
            willDoNothing().given(paymentValidator).validateAmount(payment, 10000);
            given(paymentClientFactory.getClient("TOSS")).willReturn(paymentClient);
            willThrow(new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED))
                    .given(paymentClient).confirmPayment("pk", "ORD-X", 10000);

            // when / then
            assertThatThrownBy(() -> paymentService.confirmPayment("pk", "ORD-X", 10000))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_APPROVAL_FAILED);
            then(payment).should(never()).approve(any(), any());
            then(eventPublisher).should(never()).publishEvent(any());
        }
    }
}
