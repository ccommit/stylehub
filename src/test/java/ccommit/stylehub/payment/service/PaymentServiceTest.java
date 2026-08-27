package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.payment.client.PaymentClient;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.enums.PaymentStatus;
import ccommit.stylehub.payment.event.PaymentApprovedEvent;
import ccommit.stylehub.payment.event.PaymentFailedEvent;
import ccommit.stylehub.payment.event.PaymentFullyCanceledEvent;
import ccommit.stylehub.payment.policy.PaymentValidator;
import ccommit.stylehub.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * PaymentService의 결제 준비/승인/취소/실패 처리 로직을 검증하는 단위테스트이다.
 * PG사 호출(PaymentClient)과 검증(PaymentValidator), Repository, 이벤트 발행기는 Mock으로 대체한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
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
    private PaymentClient tossClient;

    @InjectMocks
    private PaymentService paymentService;

    private Order orderWithStatus(long orderId, OrderStatus status) {
        Order order = Order.builder().orderStatus(status).build();
        ReflectionTestUtils.setField(order, "orderId", orderId);
        return order;
    }

    private Payment payment(PaymentStatus status, Order order, int requestedAmount, int balanceAmount) {
        Payment payment = Payment.create(order, "key", "주문 결제", requestedAmount, requestedAmount, balanceAmount);
        ReflectionTestUtils.setField(payment, "status", status);
        return payment;
    }

    @Nested
    @DisplayName("createReady")
    class CreateReady {

        @Test
        @DisplayName("주문 ID로 Order 참조를 얻어 READY 상태 결제를 생성한다")
        void READY_상태_결제를_생성한다() {
            // given
            Order orderRef = orderWithStatus(1L, OrderStatus.PENDING);
            when(em.getReference(Order.class, 1L)).thenReturn(orderRef);

            // when
            paymentService.createReady(1L, 10000, 9000);

            // then
            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository).save(captor.capture());
            Payment saved = captor.getValue();
            assertThat(saved.getOrder()).isEqualTo(orderRef);
            assertThat(saved.getOrderName()).isEqualTo("주문 결제");
            assertThat(saved.getRequestedAmount()).isEqualTo(9000);
            assertThat(saved.getTotalAmount()).isEqualTo(10000);
            assertThat(saved.getBalanceAmount()).isEqualTo(9000);
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.READY);
        }
    }

    @Nested
    @DisplayName("confirmPayment")
    class ConfirmPayment {

        @Test
        @DisplayName("검증과 PG 승인을 통과하면 결제를 승인 처리하고 주문을 결제완료로 전환한다")
        void 정상적으로_승인처리한다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PENDING);
            Payment payment = payment(PaymentStatus.READY, order, 10000, 10000);
            when(paymentRepository.findByOrderPgOrderId("ORD-1")).thenReturn(Optional.of(payment));
            when(paymentClientFactory.getClient("TOSS")).thenReturn(tossClient);

            // when
            PaymentResponse response = paymentService.confirmPayment("pk-1", "ORD-1", 10000);

            // then
            assertThat(response.status()).isEqualTo(PaymentStatus.DONE);
            assertThat(payment.getPaymentKey()).isEqualTo("pk-1");
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
            verify(tossClient).confirmPayment("pk-1", "ORD-1", 10000);
            verify(eventPublisher).publishEvent(new PaymentApprovedEvent(1L));
        }

        @Test
        @DisplayName("결제를 찾을 수 없으면 PAYMENT_NOT_FOUND 예외가 발생한다")
        void 결제가_없으면_예외() {
            // given
            when(paymentRepository.findByOrderPgOrderId("NONE")).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> paymentService.confirmPayment("pk", "NONE", 1000))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("승인 불가 상태면 PG 호출 없이 예외가 전파된다")
        void 승인불가_상태면_PG를_호출하지_않는다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PAID);
            Payment payment = payment(PaymentStatus.DONE, order, 10000, 10000);
            when(paymentRepository.findByOrderPgOrderId("ORD-1")).thenReturn(Optional.of(payment));
            doThrow(new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED))
                    .when(paymentValidator).validateApprovable(payment);

            // when & then
            assertThatThrownBy(() -> paymentService.confirmPayment("pk", "ORD-1", 10000))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED);
            verify(paymentClientFactory, never()).getClient(anyString());
        }

        @Test
        @DisplayName("금액이 일치하지 않으면 PG 호출 없이 예외가 전파된다")
        void 금액불일치면_PG를_호출하지_않는다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PENDING);
            Payment payment = payment(PaymentStatus.READY, order, 10000, 10000);
            when(paymentRepository.findByOrderPgOrderId("ORD-1")).thenReturn(Optional.of(payment));
            doThrow(new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH))
                    .when(paymentValidator).validateAmount(payment, 9999);

            // when & then
            assertThatThrownBy(() -> paymentService.confirmPayment("pk", "ORD-1", 9999))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
            verify(paymentClientFactory, never()).getClient(anyString());
        }

        @Test
        @DisplayName("PG 승인이 실패하면 결제/주문 상태를 변경하지 않고 예외가 전파된다")
        void PG승인실패시_상태를_변경하지_않는다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PENDING);
            Payment payment = payment(PaymentStatus.READY, order, 10000, 10000);
            when(paymentRepository.findByOrderPgOrderId("ORD-1")).thenReturn(Optional.of(payment));
            when(paymentClientFactory.getClient("TOSS")).thenReturn(tossClient);
            doThrow(new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED))
                    .when(tossClient).confirmPayment("pk", "ORD-1", 10000);

            // when & then
            assertThatThrownBy(() -> paymentService.confirmPayment("pk", "ORD-1", 10000))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_APPROVAL_FAILED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("cancelPayment")
    class CancelPayment {

        @Test
        @DisplayName("전액 취소하면 결제 상태가 CANCELED가 되고 전액취소 이벤트가 발행된다")
        void 전액취소시_이벤트가_발행된다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PREPARING);
            Payment payment = payment(PaymentStatus.DONE, order, 10000, 10000);
            ReflectionTestUtils.setField(payment, "approvedAmount", 10000);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
            when(paymentClientFactory.getClient("TOSS")).thenReturn(tossClient);

            // when
            PaymentResponse response = paymentService.cancelPayment(1L, "단순 변심", null);

            // then
            assertThat(response.status()).isEqualTo(PaymentStatus.CANCELED);
            verify(tossClient).cancelPayment(payment.getPaymentKey(), "단순 변심", null);
            verify(eventPublisher).publishEvent(new PaymentFullyCanceledEvent(1L));
        }

        @Test
        @DisplayName("부분 취소하면 PARTIAL_CANCELED 상태가 되고 전액취소 이벤트는 발행되지 않는다")
        void 부분취소시_이벤트가_발행되지_않는다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PREPARING);
            Payment payment = payment(PaymentStatus.DONE, order, 10000, 10000);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
            when(paymentClientFactory.getClient("TOSS")).thenReturn(tossClient);

            // when
            PaymentResponse response = paymentService.cancelPayment(1L, "부분 반품", 3000);

            // then
            assertThat(response.status()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("결제를 찾을 수 없으면 PAYMENT_NOT_FOUND 예외가 발생한다")
        void 결제가_없으면_예외() {
            // given
            when(paymentRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> paymentService.cancelPayment(999L, "사유", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("검증에 실패하면 PG를 호출하지 않는다")
        void 검증실패시_PG를_호출하지_않는다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.SHIPPING);
            Payment payment = payment(PaymentStatus.DONE, order, 10000, 10000);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
            doThrow(new BusinessException(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING))
                    .when(paymentValidator).validateCancel(payment, null);

            // when & then
            assertThatThrownBy(() -> paymentService.cancelPayment(1L, "사유", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
            verify(paymentClientFactory, never()).getClient(anyString());
        }

        @Test
        @DisplayName("PG 취소가 실패하면 결제 상태를 변경하지 않고 예외가 전파된다")
        void PG취소실패시_상태를_변경하지_않는다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PREPARING);
            Payment payment = payment(PaymentStatus.DONE, order, 10000, 10000);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
            when(paymentClientFactory.getClient("TOSS")).thenReturn(tossClient);
            doThrow(new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED))
                    .when(tossClient).cancelPayment(any(), any(), any());

            // when & then
            assertThatThrownBy(() -> paymentService.cancelPayment(1L, "사유", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_CANCEL_FAILED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("handlePaymentFailure")
    class HandlePaymentFailure {

        @Test
        @DisplayName("결제를 실패 처리하고 실패 이벤트를 발행한다")
        void 결제를_실패처리한다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PENDING);
            Payment payment = payment(PaymentStatus.READY, order, 10000, 10000);
            when(paymentRepository.findByOrderPgOrderId("ORD-1")).thenReturn(Optional.of(payment));

            // when
            paymentService.handlePaymentFailure("ORD-1");

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ABORTED);
            verify(eventPublisher).publishEvent(new PaymentFailedEvent(1L));
        }

        @Test
        @DisplayName("결제를 찾을 수 없으면 PAYMENT_NOT_FOUND 예외가 발생한다")
        void 결제가_없으면_예외() {
            // given
            when(paymentRepository.findByOrderPgOrderId("NONE")).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> paymentService.handlePaymentFailure("NONE"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
        }
    }
}
