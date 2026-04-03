package ccommit.stylehub.order.scheduler;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/01 by WonJin - refactor: OrderTimeoutManager → OrderPaymentTimeout 네이밍 변경
 *
 * <p>
 * 결제 대기 타임아웃 타이머를 Redis ZSET으로 관리한다.
 * 주문 생성 시 타이머를 등록하고, 결제 완료/취소 시 타이머를 제거한다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class OrderPaymentTimeout {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentTimeout.class);
    private static final long TIMEOUT_MILLIS = 10 * 60 * 1000; // 10분

    private final StringRedisTemplate redisTemplate;

    /**
     * 주문 생성 시 타임아웃 타이머를 등록한다.
     * score = 현재 시각 + 30분 (만료 시각)
     */
    public void registerTimeout(Long orderId) {
        double expireAt = System.currentTimeMillis() + TIMEOUT_MILLIS;
        redisTemplate.opsForZSet().add(
                OrderTimeoutScheduler.ORDER_TIMEOUT_KEY,
                String.valueOf(orderId),
                expireAt
        );
        log.debug("주문 결제 타임아웃 등록: orderId={}, expireAt={}ms 후", orderId, TIMEOUT_MILLIS);
    }

    // 결제 완료 또는 주문 취소 시 타임아웃 타이머를 제거한다.
    public void removeTimeout(Long orderId) {
        redisTemplate.opsForZSet().remove(
                OrderTimeoutScheduler.ORDER_TIMEOUT_KEY,
                String.valueOf(orderId)
        );
        log.debug("주문 결제 타임아웃 제거: orderId={}", orderId);
    }
}
