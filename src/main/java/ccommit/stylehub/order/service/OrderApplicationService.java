package ccommit.stylehub.order.service;

import ccommit.stylehub.common.idempotency.IdempotencyGuard;
import ccommit.stylehub.common.idempotency.IdempotencyRequest;
import ccommit.stylehub.common.idempotency.IdempotentOperation;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.response.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 주문 생성 유스케이스의 Application 계층 서비스이다.
 * Idempotency-Key 로 재전송·중복 클릭이 주문과 재고 차감을 두 번 만들지 않게 하고, 주문 도메인 로직은 OrderService 에 맡긴다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class OrderApplicationService {

    private final OrderService orderService;
    private final IdempotencyGuard idempotencyGuard;

    public OrderResponse placeOrder(Long userId, String idempotencyKey, OrderCreateRequest request) {
        IdempotencyRequest idempotency = IdempotencyRequest.of(userId, IdempotentOperation.ORDER_CREATE, idempotencyKey,
                request.addressId(), request.userCouponId(), canonicalDetails(request.details()));

        return idempotencyGuard.execute(idempotency,
                () -> orderService.placeOrder(userId, request),
                OrderResponse::orderId,
                orderId -> orderService.getOrder(userId, orderId));
    }

    // 항목 순서만 다르거나 같은 옵션을 나눠 담은 요청은 같은 주문이므로, 옵션 ID 순으로 수량을 합친 값으로 비교한다.
    private String canonicalDetails(List<OrderDetailRequest> details) {
        Map<Long, Integer> quantityByOption = new TreeMap<>();
        for (OrderDetailRequest detail : details) {
            quantityByOption.merge(detail.productOptionId(), detail.quantity(), Integer::sum);
        }
        return quantityByOption.toString();
    }
}
