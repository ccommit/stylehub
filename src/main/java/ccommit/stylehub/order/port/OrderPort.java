package ccommit.stylehub.order.port;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 * @modified 2026/04/22 by WonJin - refactor: cancelPaidOrder 제거 — 내부 상태(PENDING/PAID)가 포트로 누수되지 않도록 cancelOrder로 통합
 *
 * <p>
 * Order 도메인이 외부에 제공하는 포트 인터페이스이다.
 * 주문 취소, 결제 타임아웃 제거를 제공한다.
 * 이벤트 기반 리팩터링 이후 현재는 구현체가 없고 호출부도 이벤트로 통합되어 고아 상태이며, 후속 PR에서 제거 예정이다.
 * </p>
 */
public interface OrderPort {

    void cancelOrder(Long orderId);

    void removeTimeout(Long orderId);
}
