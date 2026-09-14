package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.payment.client.PaymentClient;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
import ccommit.stylehub.payment.dto.response.PgPaymentSnapshot;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

/**
 * @author WonJin Bae
 * @created 2026/04/24
 *
 * <p>
 * PaymentService 의 단위 테스트이다.
 * 승인은 정상 / 미존재 결제 / 이미 처리된 결제 / 금액 불일치 / PG 호출 실패 경로를,
 * 만료 직전 PG 대조(reconcileIfApproved)는 승인 응답 유실 복구와 오탐 방지 경로를 검증한다.
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
    private PaymentClient tossClient;

    @InjectMocks
    private PaymentService paymentService;


    private Order orderWithStatus(long orderId, OrderStatus status) {
        Order order = Order.builder().orderStatus(status).build();
        ReflectionTestUtils.setField(order, "orderId", orderId);
        return order;
    }

    private Order orderWithPgOrderId(long orderId, OrderStatus status, String pgOrderId) {
        Order order = orderWithStatus(orderId, status);
        ReflectionTestUtils.setField(order, "pgOrderId", pgOrderId);
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

            given(paymentRepository.findByOrderPgOrderIdWithLock(pgOrderId))
                    .willReturn(Optional.of(payment));
            willDoNothing().given(paymentValidator).validateApprovable(payment);
            willDoNothing().given(paymentValidator).validateAmount(payment, tossAmount);
            given(paymentClientFactory.getClient("TOSS")).willReturn(tossClient);
            willDoNothing().given(tossClient).confirmPayment(paymentKey, pgOrderId, tossAmount);

            // when
            PaymentResponse response = paymentService.confirmPayment(paymentKey, pgOrderId, tossAmount);

            // then
            assertThat(response).isNotNull();
            then(tossClient).should().confirmPayment(paymentKey, pgOrderId, tossAmount);
            then(payment).should().approve(paymentKey, tossAmount);
            then(order).should().markPaid();
            then(eventPublisher).should().publishEvent(any(PaymentApprovedEvent.class));
        }

        @Test
        @DisplayName("pgOrderId 에 해당하는 결제가 없으면 PAYMENT_NOT_FOUND 를 던진다")
        void throwsNotFound_whenPaymentMissing() {
            // given
            given(paymentRepository.findByOrderPgOrderIdWithLock("ORD-X")).willReturn(Optional.empty());

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
            given(paymentRepository.findByOrderPgOrderIdWithLock("ORD-X")).willReturn(Optional.of(payment));
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
            given(paymentRepository.findByOrderPgOrderIdWithLock("ORD-X")).willReturn(Optional.of(payment));
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
            given(paymentRepository.findByOrderPgOrderIdWithLock("ORD-X")).willReturn(Optional.of(payment));
            willDoNothing().given(paymentValidator).validateApprovable(payment);
            willDoNothing().given(paymentValidator).validateAmount(payment, 10000);
            given(paymentClientFactory.getClient("TOSS")).willReturn(tossClient);
            willThrow(new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED))
                    .given(tossClient).confirmPayment("pk", "ORD-X", 10000);

            // when / then
            assertThatThrownBy(() -> paymentService.confirmPayment("pk", "ORD-X", 10000))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_APPROVAL_FAILED);
            then(payment).should(never()).approve(any(), any());
            then(eventPublisher).should(never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("reconcileIfApproved")
    class ReconcileIfApproved {

        // 승인 요청이 PG 에 도달했는데 응답만 유실된 상황이다.
        // 우리 DB 는 결제 대기인데 PG 는 승인 완료라, 이대로 만료시키면 결제한 주문이 취소된다.
        @Test
        @DisplayName("PG 기준 승인 완료면 우리 결제를 승인 처리하고 취소하지 말라고 알린다")
        void PG가_승인상태면_우리상태를_맞춘다() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.PENDING, "ORD-1");
            Payment payment = payment(PaymentStatus.READY, order, 10000, 10000);
            given(paymentRepository.findByOrderOrderId(1L)).willReturn(Optional.of(payment));
            given(paymentClientFactory.getClient("TOSS")).willReturn(tossClient);
            given(tossClient.findPayment("ORD-1"))
                    .willReturn(new PgPaymentSnapshot(true, "pk-1", 10000));

            // when
            boolean approved = paymentService.reconcileIfApproved(1L);

            // then
            assertThat(approved).isTrue();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat(payment.getPaymentKey()).isEqualTo("pk-1");
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
            then(eventPublisher).should().publishEvent(new PaymentApprovedEvent(1L));
        }

        @Test
        @DisplayName("PG 에 결제 기록이 없으면 승인 처리하지 않고 취소를 막지 않는다")
        void PG에_기록이_없으면_취소를_막지_않는다() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.PENDING, "ORD-1");
            Payment payment = payment(PaymentStatus.READY, order, 10000, 10000);
            given(paymentRepository.findByOrderOrderId(1L)).willReturn(Optional.of(payment));
            given(paymentClientFactory.getClient("TOSS")).willReturn(tossClient);
            given(tossClient.findPayment("ORD-1")).willReturn(PgPaymentSnapshot.notFound());

            // when
            boolean approved = paymentService.reconcileIfApproved(1L);

            // then
            assertThat(approved).isFalse();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("결제 레코드 자체가 없으면 PG 를 조회하지 않는다")
        void 결제가_없으면_PG를_조회하지_않는다() {
            // given
            given(paymentRepository.findByOrderOrderId(1L)).willReturn(Optional.empty());

            // when
            boolean approved = paymentService.reconcileIfApproved(1L);

            // then
            assertThat(approved).isFalse();
            then(paymentClientFactory).should(never()).getClient(anyString());
        }

        // 이미 승인·취소로 끝난 건은 대조 대상이 아니다. 만료 배치가 돌 때마다
        // 종료된 결제까지 PG 에 물어보면 외부 호출만 늘어난다.
        @Test
        @DisplayName("이미 승인이 끝난 결제는 PG 를 조회하지 않는다")
        void 이미_처리된_결제는_PG를_조회하지_않는다() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.PAID, "ORD-1");
            Payment payment = payment(PaymentStatus.DONE, order, 10000, 10000);
            given(paymentRepository.findByOrderOrderId(1L)).willReturn(Optional.of(payment));

            // when
            boolean approved = paymentService.reconcileIfApproved(1L);

            // then
            assertThat(approved).isFalse();
            then(paymentClientFactory).should(never()).getClient(anyString());
        }

        // PG 를 거쳐 들어온 값이라도 우리가 저장해둔 요청 금액과 다르면 승인하지 않는다.
        // 승인 콜백과 같은 기준을 적용해, 대조 경로가 금액 검증의 우회로가 되지 않게 한다.
        @Test
        @DisplayName("PG 금액이 요청 금액과 다르면 승인하지 않고 예외를 던진다")
        void 금액이_다르면_승인하지_않는다() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.PENDING, "ORD-1");
            Payment payment = payment(PaymentStatus.READY, order, 10000, 10000);
            given(paymentRepository.findByOrderOrderId(1L)).willReturn(Optional.of(payment));
            given(paymentClientFactory.getClient("TOSS")).willReturn(tossClient);
            given(tossClient.findPayment("ORD-1"))
                    .willReturn(new PgPaymentSnapshot(true, "pk-1", 1000));
            willThrow(new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH))
                    .given(paymentValidator).validateAmount(payment, 1000);

            // when / then
            assertThatThrownBy(() -> paymentService.reconcileIfApproved(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_AMOUNT_MISMATCH);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
            then(eventPublisher).should(never()).publishEvent(any());
        }

        // 조회에 실패한 것과 승인되지 않은 것은 다르다. 실패를 false 로 뭉개면
        // 알 수 없는 상태에서 취소해버려 막으려던 문제가 그대로 발생한다.
        @Test
        @DisplayName("PG 조회가 실패하면 삼키지 않고 예외를 전파한다")
        void PG_조회_실패는_전파한다() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.PENDING, "ORD-1");
            Payment payment = payment(PaymentStatus.READY, order, 10000, 10000);
            given(paymentRepository.findByOrderOrderId(1L)).willReturn(Optional.of(payment));
            given(paymentClientFactory.getClient("TOSS")).willReturn(tossClient);
            willThrow(new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED))
                    .given(tossClient).findPayment("ORD-1");

            // when / then
            assertThatThrownBy(() -> paymentService.reconcileIfApproved(1L))
                    .isInstanceOf(BusinessException.class);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
            then(eventPublisher).should(never()).publishEvent(any());
        }
    }
}
