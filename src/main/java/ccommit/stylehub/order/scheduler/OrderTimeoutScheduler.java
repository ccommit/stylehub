package ccommit.stylehub.order.scheduler;

import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/03/27 by WonJin - refactor: Lua 스크립트로 ZRANGEBYSCORE+ZREM 원자적 처리, 보정 스케줄러 배치 LIMIT 추가
 * @modified 2026/03/29 by WonJin - refactor: OrderTransactionService → OrderService 통합에 따른 의존성 변경
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

    private static final int TIMEOUT_MINUTES = 10;
    private static final int BATCH_SIZE = 100;

    // Lua 스크립트: ZRANGEBYSCORE + ZREM을 원자적으로 실행하여 다중 서버 중복 처리를 방지한다.
    private static final DefaultRedisScript<List> FETCH_AND_REMOVE_SCRIPT;

    static {
        FETCH_AND_REMOVE_SCRIPT = new DefaultRedisScript<>();
        FETCH_AND_REMOVE_SCRIPT.setScriptText("""
                local orders = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, ARGV[2])
                if #orders > 0 then
                    redis.call('ZREM', KEYS[1], unpack(orders))
                end
                return orders
                """);
        FETCH_AND_REMOVE_SCRIPT.setResultType(List.class);
    }

    private final StringRedisTemplate redisTemplate;
    private final OrderRepository orderRepository;
    private final OrderService orderService;

    /**
     * Redis ZSET에서 만료된 주문을 1분마다 폴링하여 취소 처리한다.
     * Lua 스크립트로 조회+제거를 원자적으로 수행하여 다중 서버 중복 처리를 방지한다.
     * TODO: 주문 취소 시 유저 메일 발송 추가 필요
     */
    @Scheduled(fixedDelay = 60000)
    @SuppressWarnings("unchecked")
    public void cancelExpiredOrders() {
        long now = System.currentTimeMillis();

        List<String> expiredOrderIds = redisTemplate.execute(
                FETCH_AND_REMOVE_SCRIPT,
                Collections.singletonList(ORDER_TIMEOUT_KEY),
                String.valueOf(now),
                String.valueOf(BATCH_SIZE)
        );

        if (expiredOrderIds == null || expiredOrderIds.isEmpty()) {
            return;
        }

        for (String orderIdStr : expiredOrderIds) {
            Long orderId = Long.valueOf(orderIdStr);
            try {
                orderService.cancelOrder(orderId);
                log.info("주문 타임아웃 취소: orderId={}", orderId);
            } catch (Exception e) {
                log.error("주문 타임아웃 취소 실패: orderId={}, error={}", orderId, e.getMessage());
            }
        }
    }

    // Redis 타이머 누락 보정 — 서버 장애 등으로 Redis에 등록되지 못한 PENDING 주문을 1시간마다 배치 탐색하여 취소
    @Scheduled(fixedDelay = 3600000)
    public void compensateOrphanedOrders() {
        LocalDateTime expiredTime = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        List<Order> orphanedOrders = orderRepository.findExpiredOrders(
                OrderStatus.PENDING, expiredTime, PageRequest.of(0, BATCH_SIZE)
        );

        if (orphanedOrders.isEmpty()) {
            return;
        }

        log.warn("Redis 타이머 누락 보정: {}건 발견", orphanedOrders.size());

        for (Order order : orphanedOrders) {
            try {
                orderService.cancelOrder(order.getOrderId());
                log.info("보정 취소 완료: orderId={}", order.getOrderId());
            } catch (Exception e) {
                log.error("보정 취소 실패: orderId={}, error={}", order.getOrderId(), e.getMessage());
            }
        }
    }
}
