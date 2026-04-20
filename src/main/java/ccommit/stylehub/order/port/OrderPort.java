package ccommit.stylehub.order.port;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 *
 * <p>
 * Order 도메인이 외부에 제공하는 포트 인터페이스이다.
 * 주문 취소, 결제 타임아웃 제거를 제공한다.
 * </p>
 */
public interface OrderPort {

    void cancelOrder(Long orderId);

    void cancelPaidOrder(Long orderId);

    void removeTimeout(Long orderId);
}
