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
import ccommit.stylehub.user.enums.UserRole;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * @modified 2026/09/17 by WonJin - fix: cancelPayment 에 요청자 권한 검증 추가 — 주문자 본인/관리자만 취소 가능 (타인 결제 취소 차단)
 * @modified 2026/09/17 by WonJin - fix: 실패 콜백을 승인 대기 결제에만 반영 — 승인된 결제가 환불 없이 ABORTED·주문 취소되던 문제 해결
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

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

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
    // 권한 검증을 상태 검증보다 먼저 수행해, 권한 없는 요청자에게 남의 주문·배송·결제 상태가 응답으로 노출되지 않게 한다.
    // 결제 존재 여부는 404(없음)와 403(타인)의 차이로 드러나는데, 주문 조회 API 와 같은 기준을 유지했다.
    @Transactional
    public PaymentResponse cancelPayment(Long paymentId, Long requesterId, UserRole requesterRole,
                                         String cancelReason, Integer cancelAmount) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        paymentValidator.validateCancelAuthority(payment, requesterId, requesterRole);
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

    /**
     * 토스 결제창에서 사용자가 취소하거나 인증에 실패하면 failUrl(/fail)로 리다이렉트되어 호출된다.
     *
     * <p>이 경로는 인증 없이 열려 있고 누구나 pgOrderId 로 호출할 수 있다. 그래서 승인 대기(READY, IN_PROGRESS)
     * 결제에만 반영하고, 이미 승인·취소된 결제에 대한 호출은 아무것도 바꾸지 않고 끝낸다(멱등).
     * 승인 콜백과 동시에 들어와도 한쪽만 반영되도록 승인 경로와 같은 비관적 락으로 조회한다.
     */
    @Transactional
    public void handlePaymentFailure(String pgOrderId) {
        Payment payment = paymentRepository.findByOrderPgOrderIdWithLock(pgOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (!payment.isAwaitingApproval()) {
            log.info("승인 대기 상태가 아닌 결제의 실패 콜백 무시: pgOrderId={}, status={}", pgOrderId, payment.getStatus());
            return;
        }

        payment.abort();
        eventPublisher.publishEvent(new PaymentFailedEvent(payment.getOrder().getOrderId()));
    }
}
