package ccommit.stylehub.payment.event;

import ccommit.stylehub.order.event.OrderPlacedEvent;
import ccommit.stylehub.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * @author WonJin Bae
 * @created 2026/04/22
 *
 * <p>
 * Order 도메인이 발행한 이벤트를 수신해 Payment 도메인 측 후속 작업을 수행한다.
 * 동기(@EventListener) 처리로 주문 생성 트랜잭션에 참여하여 Payment READY 엔티티를 함께 저장한다.
 * OrderService가 PaymentService를 직접 주입하지 않도록 하여 순환 의존성을 제거한다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final PaymentService paymentService;

    @EventListener
    public void on(OrderPlacedEvent event) {
        paymentService.createReady(event.orderId(), event.totalAmount(), event.finalAmount());
    }
}
