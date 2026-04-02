package ccommit.stylehub.order.event;

/**
 * @author WonJin Bae
 * @created 2026/03/29
 *
 * <p>
 * 주문 생성 트랜잭션이 커밋된 후 발행되는 이벤트이다.
 * Redis 타임아웃 등록 등 트랜잭션 밖에서 처리할 작업을 트리거한다.
 * </p>
 */
public record OrderCreatedEvent(
        Long orderId
) {
}
