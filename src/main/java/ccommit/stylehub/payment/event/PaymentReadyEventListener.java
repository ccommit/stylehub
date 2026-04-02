package ccommit.stylehub.payment.event;

import ccommit.stylehub.order.event.OrderCreatedEvent;
import ccommit.stylehub.payment.entity.Payment;
import ccommit.stylehub.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * @author WonJin Bae
 * @created 2026/04/02
 *
 * <p>
 * 주문 생성 이벤트를 수신하여 Payment를 READY 상태로 생성한다.
 * BEFORE_COMMIT으로 주문과 같은 트랜잭션에서 처리되어 원자성이 보장된다.
 * OrderService가 PaymentRepository에 직접 의존하지 않도록 이벤트로 분리했다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class PaymentReadyEventListener {

    private final PaymentRepository paymentRepository;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void handleOrderCreated(OrderCreatedEvent event) {
        paymentRepository.save(Payment.create(
                event.order(), "", "주문 결제",
                event.finalAmount(), event.totalAmount(), event.finalAmount()
        ));
    }
}
