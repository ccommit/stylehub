package ccommit.stylehub.payment.event;

/**
 * @author WonJin Bae
 * @created 2026/04/22
 *
 * <p>
 * 결제창에서 사용자가 취소/실패해 failUrl로 리다이렉트된 시점에 발행되는 도메인 이벤트.
 * Order 도메인의 동기 리스너에서 주문 취소(재고 복구), AFTER_COMMIT 리스너에서 Redis 타임아웃 제거를 수행한다.
 * </p>
 */
public record PaymentFailedEvent(Long orderId) {
}
