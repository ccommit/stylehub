package ccommit.stylehub.order.event;

import ccommit.stylehub.order.scheduler.OrderPaymentTimeout;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * @author WonJin Bae
 * @created 2026/03/29
 *
 * <p>
 * 주문 생성 이벤트를 수신하여 트랜잭션 커밋 후 Redis 타임아웃을 등록한다.
 * 트랜잭션이 롤백되면 이벤트가 발행되지 않아 Redis 정합성이 보장된다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class OrderCreatedEventListener {

    private final OrderPaymentTimeout orderPaymentTimeout;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        orderPaymentTimeout.registerTimeout(event.orderId());
    }
}
