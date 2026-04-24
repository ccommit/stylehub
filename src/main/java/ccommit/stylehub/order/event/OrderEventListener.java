package ccommit.stylehub.order.event;

import ccommit.stylehub.order.scheduler.OrderPaymentTimeout;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.payment.event.PaymentApprovedEvent;
import ccommit.stylehub.payment.event.PaymentFailedEvent;
import ccommit.stylehub.payment.event.PaymentFullyCanceledEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * @author WonJin Bae
 * @created 2026/04/22
 *
 * <p>
 * Order 도메인과 Payment 도메인의 이벤트를 수신해 Order 측 후속 작업을 수행한다.
 *
 * Redis 타임아웃 등록/제거는 AFTER_COMMIT 시점에만 수행해 트랜잭션 롤백 시 Redis와 DB 상태가 어긋나는 것을 막는다.
 * 주문 상태 전이(취소 등)는 동기 리스너로 처리해 Payment 트랜잭션에 참여하여 원자성을 보장한다.
 *
 * OrderService가 PaymentService를 직접 주입하지 않도록 중간에서 매개해 순환 의존성을 제거한다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final OrderService orderService;
    private final OrderPaymentTimeout orderPaymentTimeout;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlacedEvent event) {
        orderPaymentTimeout.registerTimeout(event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        orderPaymentTimeout.removeTimeout(event.orderId());
    }

    @EventListener
    public void onPaymentFailed(PaymentFailedEvent event) {
        orderService.cancelOrder(event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailedAfterCommit(PaymentFailedEvent event) {
        orderPaymentTimeout.removeTimeout(event.orderId());
    }

    @EventListener
    public void onPaymentFullyCanceled(PaymentFullyCanceledEvent event) {
        orderService.cancelOrder(event.orderId());
    }
}
