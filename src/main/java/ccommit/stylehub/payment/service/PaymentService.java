package ccommit.stylehub.payment.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.payment.client.PaymentClientFactory;
import ccommit.stylehub.payment.dto.response.PaymentResponse;
import ccommit.stylehub.payment.dto.response.PgPaymentSnapshot;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.enums.PaymentStatus;
import ccommit.stylehub.payment.port.PaymentPort;
import ccommit.stylehub.payment.event.PaymentApprovedEvent;
import ccommit.stylehub.payment.event.PaymentFailedEvent;
import ccommit.stylehub.payment.event.PaymentFullyCanceledEvent;
import ccommit.stylehub.payment.policy.PaymentValidator;
import ccommit.stylehub.payment.repository.PaymentRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 * @modified 2026/04/01 by WonJin - refactor: 검증 로직을 PaymentValidator로 분리
 * @modified 2026/04/22 by WonJin - refactor: OrderPort 직접 의존 제거, Payment 이벤트 발행으로 전환 (순환 참조 해소)
 * @modified 2026/04/22 by WonJin - refactor: createReady 시그니처 primitives로 변경, Order FK는 EntityManager.getReference 프록시로 처리 (도메인 경계 누수 해소)
 * @modified 2026/09/08 by WonJin - feat: reconcileIfApproved 구현 — 만료 처리 직전 PG 결제 상태 대조로 승인 응답 유실 구간 축소
 * @modified 2026/05/01 by WonJin - fix: confirmPayment 동시 호출 멱등성 보장 — findByOrderPgOrderIdWithLock 으로 비관적 락 조회 도입 (PaymentIdempotencyTest.concurrentIdempotency 노출 버그 해소)
 *
 * <p>
 * 결제 승인, 취소, 부분 취소를 담당한다.
 * 검증은 PaymentValidator, PG사 호출은 PaymentClientFactory에 위임한다.
 * Order 도메인과는 ApplicationEventPublisher를 통해서만 통신해 순환 의존성을 제거했다.
 * 외부 계약에서는 Order 엔티티를 받지 않고, 필요 시 EntityManager.getReference로 FK 프록시만 얻어 사용한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class PaymentService implements PaymentPort {

    private final PaymentRepository paymentRepository;
    private final PaymentClientFactory paymentClientFactory;
    private final PaymentValidator paymentValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager em;

    @Override
    public void createReady(Long orderId, int totalAmount, int finalAmount) {
        Order orderRef = em.getReference(Order.class, orderId);
        paymentRepository.save(Payment.create(
                orderRef, "", "주문 결제", finalAmount, totalAmount, finalAmount
        ));
    }

    /**
     * 만료 처리 직전에 PG 쪽 결제 상태를 대조한다.
     *
     * <p>승인 요청이 PG 에 도달했는데 응답만 유실되면 우리 DB 에는 결제 대기로 남는다.
     * 그대로 만료 시간이 지나면 사용자는 결제했는데 주문은 취소되고 재고까지 복구된다.
     * 취소하기 전에 한 번 확인해 그 경우를 걸러낸다.
     *
     * <p>조회 실패는 삼키지 않고 그대로 던진다. 조회에 실패한 것과 승인되지 않은 것은 다르다.
     * 알 수 없는 상태에서 취소해버리면 막으려던 문제가 그대로 발생하므로,
     * 호출자가 이번 회차를 건너뛰고 다음에 다시 시도하도록 한다.
     *
     * <p>금액은 승인 콜백과 동일하게 검증한다. PG 를 통해 들어온 값이라도 저장해둔 요청 금액과
     * 다르면 승인 처리하지 않는다.
     */
    @Override
    @Transactional
    public boolean reconcileIfApproved(Long orderId) {
        Payment payment = paymentRepository.findByOrderOrderId(orderId).orElse(null);
        if (payment == null) {
            return false;
        }

        // 이미 승인·취소 등으로 처리가 끝난 건은 대조 대상이 아니다.
        if (payment.getStatus() != PaymentStatus.READY && payment.getStatus() != PaymentStatus.IN_PROGRESS) {
            return false;
        }

        PgPaymentSnapshot snapshot = paymentClientFactory.getClient("TOSS")
                .findPayment(payment.getOrder().getPgOrderId());

        if (!snapshot.approved()) {
            return false;
        }

        paymentValidator.validateAmount(payment, snapshot.totalAmount());
        approvePayment(payment, snapshot.paymentKey(), snapshot.totalAmount());
        return true;
    }

    // 토스 결제를 확인하고 우리 DB에 승인 처리한다.
    // 같은 paymentKey 콜백이 동시에 여러 번 도착해도 1건만 승인되도록 비관적 락으로 조회한다.
    // 2번째 이후 스레드는 락 해제 시점에 status=DONE 을 보고 validateApprovable 에서 PAYMENT_ALREADY_PROCESSED 로 거절된다.
    @Transactional
    public PaymentResponse confirmPayment(String paymentKey, String pgOrderId, Integer tossAmount) {
        Payment payment = paymentRepository.findByOrderPgOrderIdWithLock(pgOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        paymentValidator.validateApprovable(payment);
        paymentValidator.validateAmount(payment, tossAmount);

        paymentClientFactory.getClient("TOSS").confirmPayment(paymentKey, pgOrderId, tossAmount);

        return approvePayment(payment, paymentKey, tossAmount);
    }

    // 토스 confirm 성공 후 우리 DB에 결제 승인을 반영한다.
    private PaymentResponse approvePayment(Payment payment, String paymentKey, Integer amount) {
        payment.approve(paymentKey, amount);
        payment.getOrder().markPaid();
        eventPublisher.publishEvent(new PaymentApprovedEvent(payment.getOrder().getOrderId()));

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
        if (payment.isFullyCanceled()) {
            eventPublisher.publishEvent(new PaymentFullyCanceledEvent(payment.getOrder().getOrderId()));
        }

        return PaymentResponse.from(payment);
    }

    // 토스 결제창에서 사용자가 취소/실패 시 failUrl(/fail)로 리다이렉트되어 호출된다.
    // confirmPayment()와 별도 요청이므로 독립 메서드로 존재한다.
    @Transactional
    public void handlePaymentFailure(String pgOrderId) {
        Payment payment = findPaymentByOrderId(pgOrderId);

        payment.abort();
        eventPublisher.publishEvent(new PaymentFailedEvent(payment.getOrder().getOrderId()));
    }

    private Payment findPaymentByOrderId(String pgOrderId) {
        return paymentRepository.findByOrderPgOrderId(pgOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}
