package ccommit.stylehub.payment.event;

/**
 * @author WonJin Bae
 * @created 2026/04/22
 *
 * <p>
 * 결제가 전액 취소된 시점에 발행되는 도메인 이벤트.
 * Order 도메인의 동기 리스너에서 결제 완료 주문을 취소 상태로 전이한다.
 * </p>
 */
public record PaymentFullyCanceledEvent(Long orderId) {
}
