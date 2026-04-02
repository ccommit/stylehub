package ccommit.stylehub.order.service;

import ccommit.stylehub.common.aop.ExecutionTimeCheck;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderItemRequest;
import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.order.dto.response.OrderItemResponse;
import ccommit.stylehub.order.dto.response.OrderListResponse;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.dto.response.OrderTotalAmountDto;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderItem;
import ccommit.stylehub.order.repository.OrderItemRepository;
import ccommit.stylehub.order.repository.OrderQueryRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.order.event.OrderCreatedEvent;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.service.ProductService;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/03/29 by WonJin - refactor: OrderTransactionService, OrderViewService를 OrderService로 통합
 *
 * <p>
 * 주문 생성, 취소, 조회를 담당한다.
 * 주문/결제 API는 ApiLoggingAspect에 의해 요청/응답이 자동 로깅된다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderQueryRepository orderQueryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final UserService userService;
    private final ProductService productService;

    /**
     * 주문을 접수한다.
     * Order.create()는 엔티티 객체 생성, placeOrder()는 주문 접수 비즈니스 흐름을 담당한다.
     * 1. 주문 생성 + 재고 차감 + Payment READY 상태로 저장
     * 2. 트랜잭션 커밋 후: OrderCreatedEvent → Redis 타임아웃 등록 (30분)
     * 3. 프론트(샌드박스)에서 pgOrderId + totalAmount로 토스 결제창 진입
     */
    @ExecutionTimeCheck(threshold = 3000)
    @Transactional
    public OrderResponse placeOrder(Long userId, OrderCreateRequest request) {
        Address address = userService.findAddressByOwner(userId, request.addressId());

        // TODO: 쿠폰 유효성 검증 + 할인 금액 계산
        // TODO: 포인트 잔액 확인 + 차감

        Order savedOrder = orderRepository.save(Order.create(address.getUser(), address));
        List<OrderItem> savedItems = decreaseStockAndCreateItems(savedOrder, request.items());

        // TODO: 쿠폰 사용 처리 (UserCoupon 상태 변경)
        // TODO: 포인트 차감 처리 (User.pointBalance 차감 + PointHistory 기록)

        int totalAmount = savedItems.stream()
                .mapToInt(OrderItem::getTotalPrice)
                .sum();
        int finalAmount = savedOrder.calculateFinalAmount(totalAmount);

        eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder, totalAmount, finalAmount));

        return buildOrderResponse(savedOrder, savedItems);
    }

    /**
     * PENDING 상태인 주문을 취소하고 재고를 복구한다.
     * 상태 검증(cancel)을 먼저 수행하여 PENDING이 아닌 주문의 재고가 변경되는 것을 방지한다.
     */
    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        order.cancel();
        restoreStock(orderId);
    }

    private void restoreStock(Long orderId) {
        List<OrderItem> items = orderItemRepository.findByOrderIdWithDetails(orderId);

        // deadlock 방지를 위해 optionId 오름차순으로 락을 획득한다.
        items.sort((a, b) -> Long.compare(
                a.getProductOption().getProductOptionId(),
                b.getProductOption().getProductOptionId()));

        for (OrderItem item : items) {
            productService.increaseStock(
                    item.getProductOption().getProductOptionId(),
                    item.getQuantity()
            );
        }
    }

    /**
     * 내 주문 내역을 커서 기반으로 조회한다. (무한 스크롤)
     */
    @Transactional(readOnly = true)
    public CursorResponse<OrderListResponse> getMyOrders(Long userId, Long cursor, Integer size) {
        int pageSize = resolvePageSize(size);

        List<Order> orders = orderQueryRepository.findMyOrdersWithCursor(userId, cursor, pageSize + 1);
        List<OrderListResponse> orderList = toOrderListResponses(orders);

        return CursorResponse.of(orderList, pageSize, OrderListResponse::orderId);
    }

    private int resolvePageSize(Integer size) {
        return (size != null && size > 0) ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
    }

    private List<OrderListResponse> toOrderListResponses(List<Order> orders) {
        List<Long> orderIds = orders.stream().map(Order::getOrderId).toList();
        Map<Long, Integer> totalAmountMap = getTotalAmountMap(orderIds);

        List<OrderListResponse> orderList = new ArrayList<>(orders.size());
        for (Order order : orders) {
            Integer totalAmount = totalAmountMap.getOrDefault(order.getOrderId(), 0);
            orderList.add(OrderListResponse.from(order, totalAmount));
        }
        return orderList;
    }

    /**
     * 주문 상세 정보를 조회한다. 본인 주문만 접근 가능.
     */
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long userId, Long orderId) {
        Order order = findOrderByOwner(userId, orderId);
        List<OrderItem> items = orderItemRepository.findByOrderIdWithDetails(orderId);
        return buildOrderResponse(order, items);
    }

    private Order findOrderByOwner(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getUser().getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED_ORDER_ACCESS);
        }
        return order;
    }

    /**
     * 같은 옵션 ID의 수량을 합산한 뒤, 재고를 차감하고 주문 항목을 생성한다.
     * deadlock 방지를 위해 optionId 오름차순으로 락을 획득한다.
     */
    private List<OrderItem> decreaseStockAndCreateItems(Order order, List<OrderItemRequest> itemRequests) {
        List<OrderItemRequest> merged = mergeAndSort(itemRequests);

        List<OrderItem> items = new ArrayList<>(merged.size());
        for (OrderItemRequest request : merged) {
            ProductOption option = productService.decreaseStockWithLock(
                    request.productOptionId(), request.quantity()
            );

            // TODO: 쿠폰 기능 구현 시 UserCoupon 전달
            UserCoupon noCoupon = null;
            items.add(OrderItem.create(
                    option, order, request.quantity(),
                    option.getProductPrice(), noCoupon
            ));
        }

        return orderItemRepository.saveAll(items);
    }

    private List<OrderItemRequest> mergeAndSort(List<OrderItemRequest> itemRequests) {
        Map<Long, Integer> merged = new TreeMap<>();
        for (OrderItemRequest request : itemRequests) {
            merged.merge(request.productOptionId(), request.quantity(), Integer::sum);
        }

        List<OrderItemRequest> result = new ArrayList<>(merged.size());
        for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
            result.add(new OrderItemRequest(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private OrderResponse buildOrderResponse(Order order, List<OrderItem> items) {
        List<OrderItemResponse> itemResponses = items.stream()
                .map(OrderItemResponse::from)
                .toList();

        int totalAmount = items.stream()
                .mapToInt(OrderItem::getTotalPrice)
                .sum();

        return OrderResponse.from(order, itemResponses, totalAmount, order.calculateFinalAmount(totalAmount));
    }

    private Map<Long, Integer> getTotalAmountMap(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> map = new HashMap<>(orderIds.size());
        for (OrderTotalAmountDto dto : orderItemRepository.calculateTotalAmounts(orderIds)) {
            map.put(dto.orderId(), dto.totalAmount().intValue());
        }
        return map;
    }
}
