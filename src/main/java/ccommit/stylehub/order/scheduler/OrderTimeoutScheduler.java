package ccommit.stylehub.order.scheduler;

import ccommit.stylehub.order.service.OrderTransactionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * Redis ZSET Delay Queue 기반 주문 타임아웃 처리기이다.
 * score를 만료 시각(epoch ms)으로 사용하여 만료된 주문만 정확히 처리한다.
 * DB 전체 스캔 없이 Redis에서 만료 주문을 탐색한다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class OrderTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutScheduler.class);
    public static final String ORDER_TIMEOUT_KEY = "order:timeout";

    private final StringRedisTemplate redisTemplate;
    private final OrderTransactionService orderTransactionService;

    /**
     * Redis ZSET에서 만료된 주문을 1초마다 폴링하여 취소 처리한다.
     * score <= 현재 시각인 주문만 조회하므로 정확한 시점에 처리된다.
     */
    @Scheduled(fixedDelay = 1000)
    public void cancelExpiredOrders() {
        long now = System.currentTimeMillis();
        Set<ZSetOperations.TypedTuple<String>> expiredOrders =
                redisTemplate.opsForZSet().rangeByScoreWithScores(ORDER_TIMEOUT_KEY, 0, now);

        if (expiredOrders == null || expiredOrders.isEmpty()) {
            return;
        }

        for (ZSetOperations.TypedTuple<String> tuple : expiredOrders) {
            String orderIdStr = tuple.getValue();
            if (orderIdStr == null) {
                continue;
            }

            Long orderId = Long.valueOf(orderIdStr);
            try {
                // ZSET에서 먼저 제거 (다른 서버가 중복 처리하지 않도록)
                Long removed = redisTemplate.opsForZSet().remove(ORDER_TIMEOUT_KEY, orderIdStr);
                if (removed == null || removed == 0) {
                    continue; // 다른 서버가 이미 처리함
                }

                orderTransactionService.cancelOrder(orderId);
                log.info("주문 타임아웃 취소: orderId={}", orderId);
            } catch (Exception e) {
                log.error("주문 타임아웃 취소 실패: orderId={}, error={}", orderId, e.getMessage());
            }
        }
    }
}
