package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.scheduler.OrderPaymentTimeout;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.policy.CancelPolicy;
import ccommit.stylehub.payment.policy.PaymentValidator;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 * @modified 2026/04/01 by WonJin - refactor: 검증 로직을 PaymentValidator로 분리
 *
 * <p>
 * 결제 승인, 취소, 부분 취소를 담당한다.
 * 검증은 PaymentValidator/CancelPolicy, PG사 호출은 PaymentClientFactory에 위임한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentClientFactory paymentClientFactory;
    private final PaymentValidator paymentValidator;
    private final CancelPolicy cancelPolicy;
    private final OrderPaymentTimeout orderPaymentTimeout;
    private final OrderService orderService;

    //결제를 승인한다.
    @Transactional
    public PaymentResponse approvePayment(String paymentKey, String pgOrderId, Integer tossAmount) {
        Payment payment = findPaymentByOrderId(pgOrderId);

        paymentValidator.validateApprovable(payment);
        paymentValidator.validateAmount(payment, tossAmount);

        paymentClientFactory.getClient("TOSS").confirmPayment(paymentKey, pgOrderId, tossAmount);

        payment.approve(paymentKey, tossAmount);
        payment.getOrder().markPaid();
        orderPaymentTimeout.removeTimeout(payment.getOrder().getOrderId());

        return PaymentResponse.from(payment);
    }

    // 결제를 취소한다
    @Transactional
    public PaymentResponse cancelPayment(Long paymentId, String cancelReason, Integer cancelAmount) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        cancelPolicy.validate(payment.getOrder());
        paymentValidator.validateCancelable(payment);
        paymentValidator.validateCancelAmount(payment, cancelAmount);

        paymentClientFactory.getClient("TOSS")
                .cancelPayment(payment.getPaymentKey(), cancelReason, cancelAmount);

        payment.cancel(cancelReason, cancelAmount);
        cancelOrderIfFullyCanceled(payment);

        return PaymentResponse.from(payment);
    }

    //결제 실패 시 주문 취소 + 재고 복구 + 타임아웃 타이머 제거를 처리한다.
    @Transactional
    public void handlePaymentFailure(String pgOrderId) {
        Payment payment = findPaymentByOrderId(pgOrderId);

        payment.abort();

        Order order = payment.getOrder();
        orderService.cancelOrder(order.getOrderId());
        orderPaymentTimeout.removeTimeout(order.getOrderId());
    }

    private void cancelOrderIfFullyCanceled(Payment payment) {
        if (payment.isFullyCanceled()) {
            orderService.cancelPaidOrder(payment.getOrder().getOrderId());
        }
    }

    private Payment findPaymentByOrderId(String pgOrderId) {
        return paymentRepository.findByOrderPgOrderId(pgOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}
