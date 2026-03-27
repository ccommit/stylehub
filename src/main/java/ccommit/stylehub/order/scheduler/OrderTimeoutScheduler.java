package ccommit.stylehub.order.scheduler;

import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.service.OrderTransactionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * Redis ZSET 기반 주문 타임아웃 처리 + DB 보정 스케줄러.
 * 1초마다 Redis에서 만료 주문을 탐색하고, 1시간마다 DB에서 누락 주문을 보정한다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class OrderTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutScheduler.class);
    public static final String ORDER_TIMEOUT_KEY = "order:timeout";

    private static final int TIMEOUT_MINUTES = 30;

    private final StringRedisTemplate redisTemplate;
    private final OrderRepository orderRepository;
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

    // Redis 타이머 누락 보정 — 서버 장애 등으로 Redis에 등록되지 못한 PENDING 주문을 1시간마다 탐색하여 취소
    @Scheduled(fixedDelay = 3600000)
    public void compensateOrphanedOrders() {
        LocalDateTime expiredTime = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        List<Order> orphanedOrders = orderRepository.findExpiredOrders(OrderStatus.PENDING, expiredTime);

        if (orphanedOrders.isEmpty()) {
            return;
        }

        log.warn("Redis 타이머 누락 보정: {}건 발견", orphanedOrders.size());

        for (Order order : orphanedOrders) {
            try {
                orderTransactionService.cancelOrder(order.getOrderId());
                log.info("보정 취소 완료: orderId={}", order.getOrderId());
            } catch (Exception e) {
                log.error("보정 취소 실패: orderId={}, error={}", order.getOrderId(), e.getMessage());
            }
        }
    }
}
