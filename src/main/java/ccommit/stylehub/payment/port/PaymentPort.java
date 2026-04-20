package ccommit.stylehub.payment.port;

import ccommit.stylehub.order.entity.Order;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 *
 * <p>
 * Payment 도메인이 외부에 제공하는 포트 인터페이스이다.
 * 결제 대기 건 생성을 제공한다.
 * </p>
 */
public interface PaymentPort {

    void createReady(Order order, int totalAmount, int finalAmount);
}
