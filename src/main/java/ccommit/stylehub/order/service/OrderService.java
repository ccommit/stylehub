package ccommit.stylehub.order.service;

import ccommit.stylehub.common.aop.ExecutionTimeCheck;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.scheduler.OrderTimeoutManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * 주문 흐름을 관리한다.
 * 트랜잭션 단위는 OrderTransactionService에서 처리하고,
 * 외부 API 호출(결제 등)과 Redis 타이머는 트랜잭션 밖에서 처리한다.
 * 주문/결제 API는 ApiLoggingAspect에 의해 요청/응답이 자동 로깅된다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderTransactionService orderTransactionService;
    private final OrderTimeoutManager orderTimeoutManager;

    /**
     * 주문을 생성한다.
     * 1. 트랜잭션: 주문 생성 + 재고 차감
     * 2. 트랜잭션 커밋 후: Redis ZSET에 타임아웃 타이머 등록 (30분)
     * 3. 추후: 토스페이먼츠 결제 API 호출
     */
    @ExecutionTimeCheck(threshold = 3000)
    public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
        // === 트랜잭션 안 ===
        OrderResponse orderResponse = orderTransactionService.createOrder(userId, request);

        // === 트랜잭션 밖 (DB 커넥션 반환된 상태) ===

        // Redis ZSET에 30분 타임아웃 타이머 등록
        orderTimeoutManager.registerTimeout(orderResponse.orderId());

        // TODO: 토스페이먼츠 결제 API 호출 (트랜잭션 밖 — 커넥션 점유 안 함)
        // paymentClient.confirm(orderResponse.pgOrderId(), orderResponse.finalAmount());

        // TODO: 위변조 검증 — 토스 반환 금액과 Payment.requestedAmount 비교, 불일치 시 결제 취소(트랜잭션 밖)

        // TODO: 결제 성공 시 타임아웃 타이머 제거(트랜잭션 밖)
        // orderTimeoutManager.removeTimeout(orderResponse.orderId());

        // TODO:   결제 실패 시 주문 취소 + 재고 복구(트랜잭션 안)
        // orderTransactionService.cancelOrder(orderResponse.orderId());
        // TODO: 타임아웃 타이머 제거(트랜잭션 밖)
        // orderTimeoutManager.removeTimeout(orderResponse.orderId());

        return orderResponse;
    }
}
