package ccommit.stylehub.order.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.response.OrderCursorResponse;
import ccommit.stylehub.order.dto.response.OrderItemResponse;
import ccommit.stylehub.order.dto.response.OrderListResponse;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderItem;
import ccommit.stylehub.order.repository.OrderItemRepository;
import ccommit.stylehub.order.repository.OrderQueryRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * 주문 내역 조회 비즈니스 로직을 처리한다. (CQRS Query)
 * </p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderViewService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderQueryRepository orderQueryRepository;

    // 내 주문 내역을 커서 기반으로 조회한다.(무한 스크롤)
    public OrderCursorResponse getMyOrders(Long userId, Long cursor, Integer size) {
        int pageSize = (size != null && size > 0) ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;

        List<Order> orders = orderQueryRepository.findMyOrdersWithCursor(userId, cursor, pageSize + 1);

        // 주문 ID 목록으로 총액을 한번에 조회 (N+1 방지)
        List<Long> orderIds = orders.stream().map(Order::getOrderId).toList();
        Map<Long, Integer> totalAmountMap = getTotalAmountMap(orderIds);

        List<OrderListResponse> orderList = new ArrayList<>(orders.size());
        for (Order order : orders) {
            Integer totalAmount = totalAmountMap.getOrDefault(order.getOrderId(), 0);
            orderList.add(OrderListResponse.from(order, totalAmount));
        }

        return OrderCursorResponse.of(orderList, pageSize);
    }

    private Map<Long, Integer> getTotalAmountMap(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> map = new HashMap<>(orderIds.size());
        for (Object[] row : orderItemRepository.calculateTotalAmounts(orderIds)) {
            map.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return map;
    }

    // 주문 상세 정보를 조회한다. 본인 주문만 접근 가능.
    public OrderResponse getOrder(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getUser().getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_ORDER_ACCESS);
        }

        List<OrderItem> items = orderItemRepository.findByOrderIdWithDetails(orderId);

        List<OrderItemResponse> itemResponses = items.stream()
                .map(OrderItemResponse::from)
                .toList();

        int totalAmount = itemResponses.stream()
                .mapToInt(OrderItemResponse::totalPrice)
                .sum();

        int finalAmount = totalAmount - order.getDiscountAmount() - order.getUsedPoint();

        return OrderResponse.from(order, itemResponses, totalAmount, finalAmount);
    }
}
