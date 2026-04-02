package ccommit.stylehub.order.event;

import ccommit.stylehub.order.entity.Order;

/**
 * @author WonJin Bae
 * @created 2026/03/29
 * @modified 2026/04/02 by WonJin - refactor: Payment 생성에 필요한 금액 정보 추가
 *
 * <p>
 * 주문 생성 시 발행되는 이벤트이다.
 * BEFORE_COMMIT: Payment READY 생성 (같은 트랜잭션)
 * AFTER_COMMIT: Redis 타임아웃 등록 (트랜잭션 밖)
 * </p>
 */
public record OrderCreatedEvent(
        Order order,
        int totalAmount,
        int finalAmount
) {
}
