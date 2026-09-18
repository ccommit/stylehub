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
import ccommit.stylehub.payment.port.PaymentPort;
import ccommit.stylehub.payment.port.PaymentReconcileResult;
import ccommit.stylehub.payment.event.PaymentApprovedEvent;
import ccommit.stylehub.payment.event.PaymentFailedEvent;
import ccommit.stylehub.payment.event.PaymentFullyCanceledEvent;
import ccommit.stylehub.payment.policy.PaymentValidator;
import ccommit.stylehub.payment.repository.PaymentRepository;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

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
 * @modified 2026/09/17 by WonJin - fix: 승인을 선점·PG 호출·반영 3단계로 분리(PG 호출 중 커넥션·락 미점유), 만료·취소된 주문 승인 차단, 만료 직전 대조 결과 세분화
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

    private static final String PG_TYPE = "TOSS";

    // 승인 요청이 PG에서 아직 처리 중일 수 있다고 보는 시간으로, 연결(3초)+읽기(10초) 타임아웃보다 넉넉하게 잡았다.
    // 이 시간이 지난 IN_PROGRESS 결제는 응답이 유실된 것으로 보고 PG 조회 결과로 결론 낸다.
    static final Duration APPROVAL_IN_FLIGHT_GRACE = Duration.ofSeconds(60);

    private static final String ORPHAN_APPROVAL_CANCEL_REASON = "결제 대기 시간이 지나 취소된 주문의 승인 건 자동 환불";

    private final PaymentRepository paymentRepository;
    private final PaymentClientFactory paymentClientFactory;
    private final PaymentValidator paymentValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager em;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void createReady(Long orderId, int totalAmount, int finalAmount) {
        Order orderRef = em.getReference(Order.class, orderId);
        paymentRepository.save(Payment.create(
                orderRef, "", "주문 결제", finalAmount, totalAmount, finalAmount
        ));
    }

    // 만료 직전 PG 상태를 대조한다. PG 조회는 트랜잭션 밖에서 하고, 반영은 결제·주문 행을 잠근 뒤 상태를 다시 확인하고 한다.
    // 조회 실패는 그대로 던져 호출자가 다음 회차에 다시 시도하게 한다.
    @Override
    public PaymentReconcileResult reconcileBeforeExpiry(Long orderId) {
        ReconcileTarget target = transactionTemplate.execute(status ->
                paymentRepository.findByOrderOrderId(orderId)
                        .map(payment -> new ReconcileTarget(payment.getStatus(), payment.getOrder().getPgOrderId()))
                        .orElse(null));

        if (target == null) {
            // 결제 레코드가 없으면 승인될 수 있는 결제도 없다.
            return PaymentReconcileResult.NOT_APPROVED;
        }
        if (target.status() != PaymentStatus.READY && target.status() != PaymentStatus.IN_PROGRESS) {
            return resultOfSettled(target.status());
        }

        // 응답을 기다리는 동안 커넥션과 행 락을 쥐지 않도록 트랜잭션 밖에서 조회한다.
        PgPaymentSnapshot snapshot = paymentClientFactory.getClient(PG_TYPE).findPayment(target.pgOrderId());

        ReconcileDecision decision = transactionTemplate.execute(status -> decideAfterLookup(orderId, snapshot));
        if (decision.refundRequired()) {
            refundOrphanApproval(snapshot.paymentKey(), target.pgOrderId());
        }
        return decision.result();
    }

    private ReconcileDecision decideAfterLookup(Long orderId, PgPaymentSnapshot snapshot) {
        Payment payment = paymentRepository.findByOrderOrderIdWithLock(orderId).orElse(null);
        if (payment == null) {
            return ReconcileDecision.of(PaymentReconcileResult.NOT_APPROVED);
        }
        Order order = lockOrder(payment);

        if (!payment.isAwaitingApproval()) {
            return ReconcileDecision.of(resultOfSettled(payment.getStatus()));
        }

        if (snapshot.approved()) {
            paymentValidator.validateAmount(payment, snapshot.totalAmount());
            if (order.getOrderStatus() != OrderStatus.PENDING) {
                // PG 에서는 승인됐는데 주문은 이미 취소돼 재고가 다시 팔렸을 수 있다. 주문을 되살리지 않고 환불한다.
                payment.expire();
                return ReconcileDecision.refund();
            }
            approvePayment(payment, snapshot.paymentKey(), snapshot.totalAmount());
            return ReconcileDecision.of(PaymentReconcileResult.APPROVED);
        }

        if (payment.isApprovalInFlight(LocalDateTime.now(), APPROVAL_IN_FLIGHT_GRACE)) {
            return ReconcileDecision.of(PaymentReconcileResult.IN_FLIGHT);
        }

        payment.expire();
        return ReconcileDecision.of(PaymentReconcileResult.NOT_APPROVED);
    }

    // 승인 대기가 아닌 결제: 만료·실패면 주문 취소 가능, 승인·취소(환불) 이력이 있으면 만료 처리 대상이 아니다.
    private PaymentReconcileResult resultOfSettled(PaymentStatus status) {
        if (status == PaymentStatus.EXPIRED || status == PaymentStatus.ABORTED) {
            return PaymentReconcileResult.NOT_APPROVED;
        }
        return PaymentReconcileResult.APPROVED;
    }

    // 선점(IN_PROGRESS 커밋) → PG 호출 → 반영으로 나눠, PG 응답을 기다리는 동안 커넥션과 행 락을 쥐지 않는다.
    // PG가 4xx로 거절하면 선점을 되돌리고, 결과를 모르면(5xx, 타임아웃) IN_PROGRESS로 남겨 만료 직전 PG 대조가 결론 낸다.
    public PaymentResponse confirmPayment(String paymentKey, String pgOrderId, Integer tossAmount) {
        PaymentClient pgClient = paymentClientFactory.getClient(PG_TYPE);

        transactionTemplate.executeWithoutResult(status -> {
            Payment payment = findWithLock(pgOrderId);
            Order order = lockOrder(payment);
            paymentValidator.validateApprovable(payment);
            paymentValidator.validateOrderPayable(order);
            paymentValidator.validateAmount(payment, tossAmount);
            payment.startApproval(paymentKey);
        });

        requestApproval(pgClient, paymentKey, pgOrderId, tossAmount);

        ApprovalOutcome outcome = transactionTemplate.execute(status -> {
            Payment payment = findWithLock(pgOrderId);
            Order order = lockOrder(payment);
            if (payment.getStatus() == PaymentStatus.DONE) {
                return ApprovalOutcome.approved(PaymentResponse.from(payment));
            }
            if (payment.getStatus() != PaymentStatus.IN_PROGRESS || order.getOrderStatus() != OrderStatus.PENDING) {
                if (payment.isAwaitingApproval()) {
                    payment.expire();
                }
                return ApprovalOutcome.refund();
            }
            return ApprovalOutcome.approved(approvePayment(payment, paymentKey, tossAmount));
        });

        if (outcome.refundRequired()) {
            refundOrphanApproval(paymentKey, pgOrderId);
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE);
        }
        return outcome.response();
    }

    private void requestApproval(PaymentClient pgClient, String paymentKey, String pgOrderId, Integer amount) {
        try {
            pgClient.confirmPayment(paymentKey, pgOrderId, amount);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PAYMENT_APPROVAL_FAILED) {
                releaseApproval(pgOrderId, e);
            }
            throw e;
        }
    }

    // PG 가 거절한 결제의 선점을 되돌린다. 되돌리지 못해도 IN_PROGRESS 결제는 만료 직전 PG 대조가 결론 내므로 거절 사유를 우선 전달한다.
    private void releaseApproval(String pgOrderId, BusinessException cause) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Payment payment = findWithLock(pgOrderId);
                if (payment.getStatus() == PaymentStatus.IN_PROGRESS) {
                    payment.revertApproval();
                }
            });
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            log.error("PG 승인 거절 후 결제 선점 해제 실패: pgOrderId={}", pgOrderId, e);
        }
    }

    // 만료·취소된 주문에 대해 PG 승인이 이뤄진 경우 환불한다. 실패하면 자동으로 수렴할 경로가 없어 사람이 확인해야 한다.
    private void refundOrphanApproval(String paymentKey, String pgOrderId) {
        try {
            paymentClientFactory.getClient(PG_TYPE).cancelPayment(paymentKey, ORPHAN_APPROVAL_CANCEL_REASON, null);
            log.warn("만료·취소된 주문에 승인된 결제를 환불: pgOrderId={}", pgOrderId);
        } catch (RuntimeException e) {
            log.error("[수동 환불 필요] 만료·취소된 주문에 승인된 결제 환불 실패: pgOrderId={}, paymentKey={}",
                    pgOrderId, paymentKey, e);
        }
    }

    // 결제·주문 승인 반영. 주문 결제 완료 전이와 이벤트 발행을 한 곳에서 처리한다.
    private PaymentResponse approvePayment(Payment payment, String paymentKey, Integer amount) {
        payment.approve(paymentKey, amount);
        payment.getOrder().markPaid();
        eventPublisher.publishEvent(new PaymentApprovedEvent(payment.getOrder().getOrderId()));

        return PaymentResponse.from(payment);
    }

    private Payment findWithLock(String pgOrderId) {
        return paymentRepository.findByOrderPgOrderIdWithLock(pgOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    // 결제 → 주문 순서로 잠근다. 주문 취소 경로(OrderService)는 주문만 잠그므로 순환 대기가 생기지 않는다.
    // 결제 조회 시점에 주문은 지연 로딩 전이라, 이 잠금 조회가 주문의 최신 상태를 읽어 온다.
    private Order lockOrder(Payment payment) {
        return em.createQuery("SELECT o FROM Order o WHERE o.orderId = :orderId", Order.class)
                .setParameter("orderId", payment.getOrder().getOrderId())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();
    }

    private record ReconcileTarget(PaymentStatus status, String pgOrderId) {
    }

    private record ReconcileDecision(PaymentReconcileResult result, boolean refundRequired) {

        static ReconcileDecision of(PaymentReconcileResult result) {
            return new ReconcileDecision(result, false);
        }

        static ReconcileDecision refund() {
            return new ReconcileDecision(PaymentReconcileResult.NOT_APPROVED, true);
        }
    }

    private record ApprovalOutcome(PaymentResponse response, boolean refundRequired) {

        static ApprovalOutcome approved(PaymentResponse response) {
            return new ApprovalOutcome(response, false);
        }

        static ApprovalOutcome refund() {
            return new ApprovalOutcome(null, true);
        }
    }

    // 토스 결제를 취소하고 우리 DB에 취소 처리한다.
    // 권한을 먼저 확인해 타인에게 주문·결제 상태가 노출되지 않게 한다
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

    // 인증 없이 열린 콜백이라 결제창 단계(READY) 결제에만 반영한다. IN_PROGRESS 는 이미 PG 에 승인을 요청한 상태라 반영하면 승인된 결제를 잃는다.
    // 승인 선점과 같은 락으로 조회해 둘 중 하나만 반영된다.
    @Transactional
    public void handlePaymentFailure(String pgOrderId) {
        Payment payment = paymentRepository.findByOrderPgOrderIdWithLock(pgOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getStatus() != PaymentStatus.READY) {
            log.info("결제창 단계가 아닌 결제의 실패 콜백 무시: pgOrderId={}, status={}", pgOrderId, payment.getStatus());
            return;
        }

        payment.abort();
        eventPublisher.publishEvent(new PaymentFailedEvent(payment.getOrder().getOrderId()));
    }
}
