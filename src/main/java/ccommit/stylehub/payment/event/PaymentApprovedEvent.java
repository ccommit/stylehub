package ccommit.stylehub.payment.event;

/**
 * @author WonJin Bae
 * @created 2026/04/22
 *
 * <p>
 * 결제 승인 성공 시 발행되는 도메인 이벤트.
 * Order 도메인의 AFTER_COMMIT 리스너에서 Redis 결제 타임아웃을 제거한다.
 * </p>
 */
public record PaymentApprovedEvent(Long orderId) {
}
