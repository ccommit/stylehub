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
import ccommit.stylehub.user.enums.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import ccommit.stylehub.payment.port.PaymentReconcileResult;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.willAnswer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
 * @modified 2026/09/17 by WonJin - test: cancelPayment 요청자(ID·역할) 파라미터 반영, 권한 검증 실패 시 PG 미호출 검증 추가
 * @modified 2026/09/17 by WonJin - test: 실패 콜백이 락 조회를 쓰고 승인 대기 결제에만 반영되는지 검증
 * @modified 2026/09/17 by WonJin - test: 승인 3단계(선점·PG 호출·반영)와 만료 직전 대조 결과(APPROVED/IN_FLIGHT/NOT_APPROVED) 검증으로 재작성
 * @modified 2026/09/17 by WonJin - test: 결제 취소가 락 조회 후 DB 반영·flush 를 PG 호출보다 먼저 하는지 검증
 *
 * <p>
 * PaymentService의 승인·취소·실패 콜백·만료 직전 PG 대조를 검증하는 단위 테스트이다.
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

    @Mock
    private TransactionTemplate transactionTemplate;

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

        private static final Long REQUESTER_ID = 1L;

        @Test
        @DisplayName("요청자 권한 검증에 실패하면 상태 검증과 PG 호출 없이 예외가 전파된다")
        void 권한검증_실패시_상태검증과_PG를_호출하지_않는다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PREPARING);
            Payment payment = payment(PaymentStatus.DONE, order, 10000, 10000);
            when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
            stubOrderLock(order);
            doThrow(new BusinessException(ErrorCode.UNAUTHORIZED_PAYMENT_ACCESS))
                    .when(paymentValidator).validateCancelAuthority(payment, 2L, UserRole.USER);

            // when & then
            assertThatThrownBy(() -> paymentService.cancelPayment(1L, 2L, UserRole.USER, "사유", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED_PAYMENT_ACCESS);
            verify(paymentValidator, never()).validateCancel(any(), any());
            verify(paymentClientFactory, never()).getClient(anyString());
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
        }

        @Test
        @DisplayName("전액 취소하면 결제 상태가 CANCELED가 되고 전액취소 이벤트가 발행된다")
        void 전액취소시_이벤트가_발행된다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PREPARING);
            Payment payment = payment(PaymentStatus.DONE, order, 10000, 10000);
            ReflectionTestUtils.setField(payment, "approvedAmount", 10000);
            when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
            stubOrderLock(order);
            when(paymentClientFactory.getClient("TOSS")).thenReturn(tossClient);

            // when
            PaymentResponse response = paymentService.cancelPayment(1L, REQUESTER_ID, UserRole.USER, "단순 변심", null);

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
            when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
            stubOrderLock(order);
            when(paymentClientFactory.getClient("TOSS")).thenReturn(tossClient);

            // when
            PaymentResponse response = paymentService.cancelPayment(1L, REQUESTER_ID, UserRole.USER, "부분 반품", 3000);

            // then
            assertThat(response.status()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("결제를 찾을 수 없으면 PAYMENT_NOT_FOUND 예외가 발생한다")
        void 결제가_없으면_예외() {
            // given
            when(paymentRepository.findByIdWithLock(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> paymentService.cancelPayment(999L, REQUESTER_ID, UserRole.USER, "사유", null))
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
            when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
            stubOrderLock(order);
            doThrow(new BusinessException(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING))
                    .when(paymentValidator).validateCancel(payment, null);

            // when & then
            assertThatThrownBy(() -> paymentService.cancelPayment(1L, REQUESTER_ID, UserRole.USER, "사유", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.CANCEL_NOT_ALLOWED_SHIPPING);
            verify(paymentClientFactory, never()).getClient(anyString());
        }

        // PG 실패 시의 롤백은 트랜잭션이 하므로 실제 DB로 PaymentCancelOrderConsistencyTest에서 검증한다.
        @Test
        @DisplayName("DB 반영(전액취소 이벤트 → 주문 취소)과 flush 를 마친 뒤 PG 취소를 호출하고, PG 실패는 그대로 전파한다")
        void DB반영후_PG를_호출하고_실패는_전파한다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PREPARING);
            Payment payment = payment(PaymentStatus.DONE, order, 10000, 10000);
            when(paymentRepository.findByIdWithLock(1L)).thenReturn(Optional.of(payment));
            stubOrderLock(order);
            when(paymentClientFactory.getClient("TOSS")).thenReturn(tossClient);
            doThrow(new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED))
                    .when(tossClient).cancelPayment(any(), any(), any());

            // when & then
            assertThatThrownBy(() -> paymentService.cancelPayment(1L, REQUESTER_ID, UserRole.USER, "사유", null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_CANCEL_FAILED);
            InOrder inOrder = inOrder(eventPublisher, em, tossClient);
            inOrder.verify(eventPublisher).publishEvent(new PaymentFullyCanceledEvent(1L));
            inOrder.verify(em).flush();
            inOrder.verify(tossClient).cancelPayment(any(), any(), any());
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
            when(paymentRepository.findByOrderPgOrderIdWithLock("ORD-1")).thenReturn(Optional.of(payment));

            // when
            paymentService.handlePaymentFailure("ORD-1");

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ABORTED);
            verify(eventPublisher).publishEvent(new PaymentFailedEvent(1L));
        }

        @Test
        @DisplayName("이미 승인된 결제에 실패 콜백이 오면 상태를 바꾸지 않고 이벤트도 발행하지 않는다")
        void 승인된_결제는_실패처리하지_않는다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PAID);
            Payment payment = payment(PaymentStatus.DONE, order, 10000, 10000);
            when(paymentRepository.findByOrderPgOrderIdWithLock("ORD-1")).thenReturn(Optional.of(payment));

            // when
            paymentService.handlePaymentFailure("ORD-1");

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("승인 요청이 진행 중(IN_PROGRESS)인 결제에 실패 콜백이 오면 반영하지 않는다")
        void 승인진행중_결제는_실패처리하지_않는다() {
            // given
            Order order = orderWithStatus(1L, OrderStatus.PENDING);
            Payment payment = payment(PaymentStatus.IN_PROGRESS, order, 10000, 10000);
            when(paymentRepository.findByOrderPgOrderIdWithLock("ORD-1")).thenReturn(Optional.of(payment));

            // when
            paymentService.handlePaymentFailure("ORD-1");

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("결제를 찾을 수 없으면 PAYMENT_NOT_FOUND 예외가 발생한다")
        void 결제가_없으면_예외() {
            // given
            when(paymentRepository.findByOrderPgOrderIdWithLock("NONE")).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> paymentService.handlePaymentFailure("NONE"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("confirmPayment (결제 승인 — 선점 → PG 호출 → 반영)")
    class ConfirmPayment {

        private static final String PAYMENT_KEY = "pk-1";
        private static final String PG_ORDER_ID = "ORD-20260917-abc";
        private static final int AMOUNT = 10_000;

        @BeforeEach
        void setUp() {
            stubTransactionTemplatePassthrough();
            given(paymentClientFactory.getClient("TOSS")).willReturn(tossClient);
        }

        @Test
        @DisplayName("정상 승인 시 IN_PROGRESS 선점 후 PG 를 호출하고, 반영 단계에서 DONE·PAID·승인 이벤트가 처리된다")
        void approvesPaymentSuccessfully() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.PENDING, PG_ORDER_ID);
            Payment payment = payment(PaymentStatus.READY, order, AMOUNT, AMOUNT);
            given(paymentRepository.findByOrderPgOrderIdWithLock(PG_ORDER_ID)).willReturn(Optional.of(payment));
            stubOrderLock(order);
            willAnswer(invocation -> {
                // PG 호출 시점에는 이미 선점이 반영돼 있어야 한다.
                assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
                return null;
            }).given(tossClient).confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT);

            // when
            PaymentResponse response = paymentService.confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT);

            // then
            assertThat(response.status()).isEqualTo(PaymentStatus.DONE);
            assertThat(payment.getPaymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PAID);
            then(tossClient).should().confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT);
            then(eventPublisher).should().publishEvent(new PaymentApprovedEvent(1L));
        }

        @Test
        @DisplayName("pgOrderId 에 해당하는 결제가 없으면 PAYMENT_NOT_FOUND 를 던지고 PG 를 호출하지 않는다")
        void throwsNotFound_whenPaymentMissing() {
            given(paymentRepository.findByOrderPgOrderIdWithLock("ORD-UNKNOWN")).willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentService.confirmPayment(PAYMENT_KEY, "ORD-UNKNOWN", AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_NOT_FOUND);
            then(tossClient).should(never()).confirmPayment(any(), any(), any());
        }

        @Test
        @DisplayName("선점 단계 검증에 실패하면 결제를 선점하지 않고 PG 도 호출하지 않는다")
        void doesNotCallPg_whenValidationFails() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.CANCELLED, PG_ORDER_ID);
            Payment payment = payment(PaymentStatus.READY, order, AMOUNT, AMOUNT);
            given(paymentRepository.findByOrderPgOrderIdWithLock(PG_ORDER_ID)).willReturn(Optional.of(payment));
            stubOrderLock(order);
            willThrow(new BusinessException(ErrorCode.ORDER_NOT_PAYABLE))
                    .given(paymentValidator).validateOrderPayable(order);

            // when & then
            assertThatThrownBy(() -> paymentService.confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_PAYABLE);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
            then(tossClient).should(never()).confirmPayment(any(), any(), any());
        }

        @Test
        @DisplayName("PG 가 승인을 거절하면 선점을 READY 로 되돌려 다시 결제할 수 있게 하고 거절 사유를 전달한다")
        void revertsToReady_whenPgDeclines() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.PENDING, PG_ORDER_ID);
            Payment payment = payment(PaymentStatus.READY, order, AMOUNT, AMOUNT);
            given(paymentRepository.findByOrderPgOrderIdWithLock(PG_ORDER_ID)).willReturn(Optional.of(payment));
            stubOrderLock(order);
            willThrow(new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED))
                    .given(tossClient).confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT);

            // when & then
            assertThatThrownBy(() -> paymentService.confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_APPROVAL_FAILED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
            assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("PG 결과를 알 수 없으면 IN_PROGRESS 로 남겨 만료 직전 PG 대조가 결론 내게 한다")
        void keepsInProgress_whenPgResultUnknown() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.PENDING, PG_ORDER_ID);
            Payment payment = payment(PaymentStatus.READY, order, AMOUNT, AMOUNT);
            given(paymentRepository.findByOrderPgOrderIdWithLock(PG_ORDER_ID)).willReturn(Optional.of(payment));
            stubOrderLock(order);
            willThrow(new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN))
                    .given(tossClient).confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT);

            // when & then
            assertThatThrownBy(() -> paymentService.confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_RESULT_UNKNOWN);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("PG 응답을 기다리는 사이 만료 직전 대조가 먼저 승인을 반영했으면 그 결과를 그대로 돌려준다")
        void returnsExistingApproval_whenReconciledDuringPgCall() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.PENDING, PG_ORDER_ID);
            Payment payment = payment(PaymentStatus.READY, order, AMOUNT, AMOUNT);
            given(paymentRepository.findByOrderPgOrderIdWithLock(PG_ORDER_ID)).willReturn(Optional.of(payment));
            stubOrderLock(order);
            willAnswer(invocation -> {
                payment.approve(PAYMENT_KEY, AMOUNT);
                order.markPaid();
                return null;
            }).given(tossClient).confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT);

            // when
            PaymentResponse response = paymentService.confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT);

            // then
            assertThat(response.status()).isEqualTo(PaymentStatus.DONE);
            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("반영 단계에서 결제가 만료돼 있으면 PG 승인을 환불하고 ORDER_NOT_PAYABLE 로 거절한다")
        void refundsPgApproval_whenExpiredBeforeApply() {
            // given
            Order order = orderWithPgOrderId(1L, OrderStatus.PENDING, PG_ORDER_ID);
            Payment payment = payment(PaymentStatus.READY, order, AMOUNT, AMOUNT);
            given(paymentRepository.findByOrderPgOrderIdWithLock(PG_ORDER_ID)).willReturn(Optional.of(payment));
            stubOrderLock(order);
            willAnswer(invocation -> {
                // 유예 시간을 넘길 만큼 지연되는 동안 만료 처리가 끝난 상황
                payment.expire();
                order.cancelUnpaid();
                return null;
            }).given(tossClient).confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT);

            // when & then
            assertThatThrownBy(() -> paymentService.confirmPayment(PAYMENT_KEY, PG_ORDER_ID, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ORDER_NOT_PAYABLE);
            then(tossClient).should().cancelPayment(eq(PAYMENT_KEY), anyString(), isNull());
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
            then(eventPublisher).should(never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("reconcileBeforeExpiry (만료 직전 PG 대조)")
    class ReconcileBeforeExpiry {

        private static final String PG_ORDER_ID = "ORD-1";

        @BeforeEach
        void setUp() {
            stubTransactionTemplatePassthrough();
            given(paymentClientFactory.getClient("TOSS")).willReturn(tossClient);
        }

        private Payment stubPayment(PaymentStatus status, OrderStatus orderStatus) {
            Order order = orderWithPgOrderId(1L, orderStatus, PG_ORDER_ID);
            Payment payment = payment(status, order, 10000, 10000);
            given(paymentRepository.findByOrderOrderId(1L)).willReturn(Optional.of(payment));
            given(paymentRepository.findByOrderOrderIdWithLock(1L)).willReturn(Optional.of(payment));
            stubOrderLock(order);
            return payment;
        }

        @Test
        @DisplayName("PG 기준 승인 완료면 우리 결제를 승인으로 맞추고 APPROVED 를 돌려준다")
        void approves_whenPgApproved() {
            // given
            Payment payment = stubPayment(PaymentStatus.READY, OrderStatus.PENDING);
            given(tossClient.findPayment(PG_ORDER_ID)).willReturn(new PgPaymentSnapshot(true, "pk-lost", 10000));

            // when
            PaymentReconcileResult result = paymentService.reconcileBeforeExpiry(1L);

            // then
            assertThat(result).isEqualTo(PaymentReconcileResult.APPROVED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
            assertThat(payment.getPaymentKey()).isEqualTo("pk-lost");
            assertThat(payment.getOrder().getOrderStatus()).isEqualTo(OrderStatus.PAID);
            then(eventPublisher).should().publishEvent(new PaymentApprovedEvent(1L));
        }

        @Test
        @DisplayName("PG 에 결제 기록이 없고 선점도 없으면 결제를 만료 처리하고 NOT_APPROVED 를 돌려준다")
        void expires_whenPgNotApproved() {
            // given
            Payment payment = stubPayment(PaymentStatus.READY, OrderStatus.PENDING);
            given(tossClient.findPayment(PG_ORDER_ID)).willReturn(PgPaymentSnapshot.notFound());

            // when
            PaymentReconcileResult result = paymentService.reconcileBeforeExpiry(1L);

            // then
            assertThat(result).isEqualTo(PaymentReconcileResult.NOT_APPROVED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("승인 요청이 유예 시간 안에 진행 중이면 결론을 미루고 IN_FLIGHT 를 돌려준다")
        void defers_whenApprovalInFlight() {
            // given
            Payment payment = stubPayment(PaymentStatus.READY, OrderStatus.PENDING);
            payment.startApproval("pk-1");
            given(tossClient.findPayment(PG_ORDER_ID)).willReturn(PgPaymentSnapshot.notFound());

            // when
            PaymentReconcileResult result = paymentService.reconcileBeforeExpiry(1L);

            // then
            assertThat(result).isEqualTo(PaymentReconcileResult.IN_FLIGHT);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("유예 시간이 지난 IN_PROGRESS 결제는 PG 에 승인이 없으면 만료 처리한다")
        void expiresStaleInProgress_whenPgNotApproved() {
            // given
            Payment payment = stubPayment(PaymentStatus.READY, OrderStatus.PENDING);
            payment.startApproval("pk-1");
            ReflectionTestUtils.setField(payment, "updatedAt", LocalDateTime.now().minusMinutes(5));
            given(tossClient.findPayment(PG_ORDER_ID)).willReturn(PgPaymentSnapshot.notFound());

            // when
            PaymentReconcileResult result = paymentService.reconcileBeforeExpiry(1L);

            // then
            assertThat(result).isEqualTo(PaymentReconcileResult.NOT_APPROVED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        }

        @Test
        @DisplayName("결제 레코드가 없으면 PG 를 조회하지 않고 NOT_APPROVED 를 돌려준다")
        void notApproved_whenPaymentMissing() {
            given(paymentRepository.findByOrderOrderId(1L)).willReturn(Optional.empty());

            assertThat(paymentService.reconcileBeforeExpiry(1L)).isEqualTo(PaymentReconcileResult.NOT_APPROVED);
            then(tossClient).should(never()).findPayment(any());
        }

        @Test
        @DisplayName("이미 승인된 결제는 PG 를 조회하지 않고 APPROVED 를 돌려준다")
        void approved_whenAlreadyDone() {
            stubPayment(PaymentStatus.DONE, OrderStatus.PAID);

            assertThat(paymentService.reconcileBeforeExpiry(1L)).isEqualTo(PaymentReconcileResult.APPROVED);
            then(tossClient).should(never()).findPayment(any());
        }

        @Test
        @DisplayName("PG 금액이 요청 금액과 다르면 승인하지 않고 예외를 던진다")
        void throws_whenAmountMismatch() {
            // given
            Payment payment = stubPayment(PaymentStatus.READY, OrderStatus.PENDING);
            given(tossClient.findPayment(PG_ORDER_ID)).willReturn(new PgPaymentSnapshot(true, "pk", 99999));
            willThrow(new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH))
                    .given(paymentValidator).validateAmount(payment, 99999);

            // when & then
            assertThatThrownBy(() -> paymentService.reconcileBeforeExpiry(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_AMOUNT_MISMATCH);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
            then(eventPublisher).should(never()).publishEvent(any());
        }

        @Test
        @DisplayName("PG 조회가 실패하면 삼키지 않고 예외를 전파하며 결제 상태를 바꾸지 않는다")
        void propagates_whenPgLookupFails() {
            // given
            Payment payment = stubPayment(PaymentStatus.READY, OrderStatus.PENDING);
            willThrow(new BusinessException(ErrorCode.PAYMENT_RESULT_UNKNOWN)).given(tossClient).findPayment(PG_ORDER_ID);

            // when / then
            assertThatThrownBy(() -> paymentService.reconcileBeforeExpiry(1L)).isInstanceOf(BusinessException.class);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.READY);
        }

        @Test
        @DisplayName("PG 에서는 승인됐는데 주문이 이미 취소돼 있으면 결제를 만료 처리하고 PG 결제를 환불한다")
        void refunds_whenPgApprovedButOrderCancelled() {
            // given
            Payment payment = stubPayment(PaymentStatus.READY, OrderStatus.CANCELLED);
            given(tossClient.findPayment(PG_ORDER_ID)).willReturn(new PgPaymentSnapshot(true, "pk-late", 10000));

            // when
            PaymentReconcileResult result = paymentService.reconcileBeforeExpiry(1L);

            // then
            assertThat(result).isEqualTo(PaymentReconcileResult.NOT_APPROVED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
            then(tossClient).should().cancelPayment(eq("pk-late"), anyString(), isNull());
        }
    }

    // TransactionTemplate 은 목이라 콜백을 실행하지 않는다. 트랜잭션 경계 안의 로직을 검증하려고 콜백을 그대로 실행시킨다.
    private void stubTransactionTemplatePassthrough() {
        willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).given(transactionTemplate).execute(any());
        willAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).given(transactionTemplate).executeWithoutResult(any());
    }

    @SuppressWarnings("unchecked")
    private void stubOrderLock(Order order) {
        TypedQuery<Order> query = mock(TypedQuery.class);
        given(em.createQuery(anyString(), eq(Order.class))).willReturn(query);
        given(query.setParameter(anyString(), any())).willReturn(query);
        given(query.setLockMode(any())).willReturn(query);
        given(query.getSingleResult()).willReturn(order);
    }
}
