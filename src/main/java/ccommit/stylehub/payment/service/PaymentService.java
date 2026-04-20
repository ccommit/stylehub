package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.port.OrderPort;
import ccommit.stylehub.payment.port.PaymentPort;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.payment.client.PaymentClientFactory;
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
 * 검증은 PaymentValidator, PG사 호출은 PaymentClientFactory에 위임한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class PaymentService implements PaymentPort {

    private final PaymentRepository paymentRepository;
    private final PaymentClientFactory paymentClientFactory;
    private final PaymentValidator paymentValidator;
    private final OrderPort orderPort;

    @Override
    public void createReady(Order order, int totalAmount, int finalAmount) {
        paymentRepository.save(Payment.create(
                order, "", "주문 결제", finalAmount, totalAmount, finalAmount
        ));
    }

    // 토스 결제를 확인하고 우리 DB에 승인 처리한다.
    @Transactional
    public PaymentResponse confirmPayment(String paymentKey, String pgOrderId, Integer tossAmount) {
        Payment payment = findPaymentByOrderId(pgOrderId);

        paymentValidator.validateApprovable(payment);
        paymentValidator.validateAmount(payment, tossAmount);

        paymentClientFactory.getClient("TOSS").confirmPayment(paymentKey, pgOrderId, tossAmount);

        return approvePayment(payment, paymentKey, tossAmount);
    }

    // 토스 confirm 성공 후 우리 DB에 결제 승인을 반영한다.
    private PaymentResponse approvePayment(Payment payment, String paymentKey, Integer amount) {
        payment.approve(paymentKey, amount);
        payment.getOrder().markPaid();
        orderPort.removeTimeout(payment.getOrder().getOrderId());

        return PaymentResponse.from(payment);
    }

    // 토스 결제를 취소하고 우리 DB에 취소 처리한다.
    @Transactional
    public PaymentResponse cancelPayment(Long paymentId, String cancelReason, Integer cancelAmount) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        paymentValidator.validateCancel(payment, cancelAmount);

        paymentClientFactory.getClient("TOSS")
                .cancelPayment(payment.getPaymentKey(), cancelReason, cancelAmount);

        return applyCancellation(payment, cancelReason, cancelAmount);
    }

    // 토스 취소 성공 후 우리 DB에 취소를 반영한다.
    private PaymentResponse applyCancellation(Payment payment, String cancelReason, Integer cancelAmount) {
        payment.cancel(cancelReason, cancelAmount);
        cancelOrderIfFullyCanceled(payment);

        return PaymentResponse.from(payment);
    }

    // 토스 결제창에서 사용자가 취소/실패 시 failUrl(/fail)로 리다이렉트되어 호출된다.
    // confirmPayment()와 별도 요청이므로 독립 메서드로 존재한다.
    @Transactional
    public void handlePaymentFailure(String pgOrderId) {
        Payment payment = findPaymentByOrderId(pgOrderId);

        payment.abort();

        Order order = payment.getOrder();
        orderPort.cancelOrder(order.getOrderId());
        orderPort.removeTimeout(order.getOrderId());
    }

    private void cancelOrderIfFullyCanceled(Payment payment) {
        if (payment.isFullyCanceled()) {
            orderPort.cancelPaidOrder(payment.getOrder().getOrderId());
        }
    }

    private Payment findPaymentByOrderId(String pgOrderId) {
        return paymentRepository.findByOrderPgOrderId(pgOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}
