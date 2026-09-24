package ccommit.stylehub.order.scheduler;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.payment.port.PaymentPort;
import ccommit.stylehub.payment.port.PaymentReconcileResult;
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
 * @modified 2026/09/08 by WonJin - feat: 취소 직전 PG 결제 상태 대조 추가 (승인 응답 유실 구간 축소)
 * @modified 2026/09/17 by WonJin - fix: 결론을 못 낸 주문은 타이머 재등록, 승인 진행 중 주문은 취소 보류, 보정 스케줄러도 PG 대조 경로 사용
 * @modified 2026/09/18 by WonJin - fix: 만료 폴링을 1분에서 1초로 줄여 만료 주문 적체와 재고 반환 지연 해소
 *
 * <p>
 * Redis ZSET 기반 주문 타임아웃 처리 + DB 보정 스케줄러.
 * 1초마다 Redis 에서 만료 주문을 꺼내 처리하고, 1시간마다 DB 에서 타이머가 빠진 주문을 보정한다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class OrderTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutScheduler.class);
    public static final String ORDER_TIMEOUT_KEY = "order:timeout";

    private static final int TIMEOUT_MINUTES = 10;
    private static final int BATCH_SIZE = 100;

    // 한 회차는 BATCH_SIZE 건만 꺼낸다. 주기가 1분이면 분당 100건이 처리 상한이라, 그보다 많이 만료되면 적체되고 그동안 재고가 묶인다.
    private static final long POLL_DELAY_MILLIS = 1_000;

    // 결론을 미룬 주문을 PG 에 다시 조회하기까지의 간격. 폴링 주기와 별개로, 같은 주문의 PG 재조회가 몰리지 않게 1분 뒤로 미룬다.
    private static final long RETRY_DELAY_MILLIS = 60_000;

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
    private final PaymentPort paymentPort;
    private final OrderPaymentTimeout orderPaymentTimeout;

    // TODO: 주문 취소 시 유저 메일 발송 추가 필요
    @Scheduled(fixedDelay = POLL_DELAY_MILLIS)
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
            expireIfUnpaid(Long.valueOf(orderIdStr));
        }
    }

    // 조회 실패는 미승인과 다르므로 취소하지 않고, Lua 스크립트가 이미 ZSET에서 꺼냈으니 타이머를 다시 등록한다.
    // 금액 불일치는 재시도로 풀리지 않고 위변조 가능성이 있어 재등록하지 않고 사람이 확인하게 남긴다.
    private void expireIfUnpaid(Long orderId) {
        try {
            PaymentReconcileResult result = paymentPort.reconcileBeforeExpiry(orderId);
            switch (result) {
                case APPROVED -> log.warn("만료 직전 PG 승인 확인 — 취소하지 않고 결제 상태를 맞춤: orderId={}", orderId);
                case IN_FLIGHT -> {
                    orderPaymentTimeout.retryAfter(orderId, RETRY_DELAY_MILLIS);
                    log.info("승인 요청 진행 중 — 만료 처리를 미룸: orderId={}", orderId);
                }
                case NOT_APPROVED -> {
                    if (orderService.cancelUnpaidOrder(orderId)) {
                        log.info("주문 타임아웃 취소: orderId={}", orderId);
                    }
                }
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PAYMENT_AMOUNT_MISMATCH) {
                log.error("[수동 확인 필요] 만료 직전 PG 대조 금액 불일치 — 취소하지 않음: orderId={}", orderId);
                return;
            }
            retryLater(orderId, e);
        } catch (Exception e) {
            retryLater(orderId, e);
        }
    }

    private void retryLater(Long orderId, Exception cause) {
        log.error("주문 타임아웃 처리 실패 — 취소하지 않고 다음 회차로 미룸: orderId={}, error={}", orderId, cause.getMessage());
        try {
            orderPaymentTimeout.retryAfter(orderId, RETRY_DELAY_MILLIS);
        } catch (Exception e) {
            // Redis 에도 다시 넣지 못하면 결제 대기 주문으로 남아 보정 스케줄러가 찾아낸다.
            log.error("주문 타임아웃 재등록 실패 — 보정 스케줄러가 처리: orderId={}", orderId, e);
        }
    }

    // 서버 장애 등으로 Redis 타이머가 누락된 결제 대기 주문을 보정한다.
    // 타이머 등록과 승인 응답이 함께 유실된 주문을 대조 없이 취소하면 결제한 주문이 사라지므로 같은 PG 대조를 거친다.
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
            expireIfUnpaid(order.getOrderId());
        }
    }
}
