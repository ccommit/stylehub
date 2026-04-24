package ccommit.stylehub.order.event;

/**
 * @author WonJin Bae
 * @created 2026/04/22
 * @modified 2026/04/22 by WonJin - refactor: Order 엔티티 직접 전달 제거, primitives로 변경 (도메인 경계 누수 해소)
 *
 * <p>
 * 주문 생성이 완료된 시점에 발행되는 도메인 이벤트.
 * 동기 리스너는 Payment READY 엔티티를 생성하고, AFTER_COMMIT 리스너는 Redis 결제 타임아웃을 등록한다.
 * Order/Payment 도메인 간 엔티티 직접 의존을 막기 위해 primitive 값만 전달한다.
 * </p>
 */
public record OrderPlacedEvent(Long orderId, int totalAmount, int finalAmount) {
}
