package ccommit.stylehub.order.service;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderItemRequest;
import ccommit.stylehub.order.dto.response.OrderItemResponse;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderItem;
import ccommit.stylehub.order.repository.OrderItemRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.product.service.ProductService;
import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * 주문 생성/취소의 트랜잭션 단위를 담당한다.
 * 비관적 락으로 재고를 차감하고, 주문 + 주문 항목을 한 트랜잭션으로 저장한다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class OrderTransactionService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserService userService;
    private final ProductService productService;

    /**
     * 주문을 생성하고 비관적 락으로 재고를 차감한다.
     * 동시에 같은 옵션을 주문해도 재고 정합성이 보장된다.
     */
    @Transactional
    public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
        Address address = userService.findAddressByOwner(userId, request.addressId());
        User user = address.getUser();

        // TODO: 쿠폰 유효성 검증 + 할인 금액 계산
        // TODO: 포인트 잔액 확인 + 차감

        Order savedOrder = orderRepository.save(Order.create(user, address));
        List<OrderItem> savedItems = decreaseStockAndCreateItems(savedOrder, request.items());

        // TODO: 쿠폰 사용 처리 (UserCoupon 상태 변경)
        // TODO: 포인트 차감 처리 (User.pointBalance 차감 + PointHistory 기록)
        // TODO: 적립 포인트 계산 (결제 완료 시 적립)

        int totalAmount = savedItems.stream()
                .mapToInt(OrderItem::getTotalPrice)
                .sum();

        List<OrderItemResponse> itemResponses = savedItems.stream()
                .map(OrderItemResponse::from)
                .toList();

        return OrderResponse.from(savedOrder, itemResponses, totalAmount, savedOrder.calculateFinalAmount(totalAmount));
    }

    // PENDING 상태인 주문을 취소하고 재고를 복구한다.
    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        order.cancel();

        List<OrderItem> items = orderItemRepository.findByOrderIdWithDetails(orderId);
        for (OrderItem item : items) {
            productService.increaseStock(
                    item.getProductOption().getProductOptionId(),
                    item.getQuantity()
            );
        }
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

            items.add(OrderItem.create(
                    option, order, request.quantity(),
                    option.getProductPrice(), null
            ));
        }

        return orderItemRepository.saveAll(items);
    }

    // 같은 optionId의 수량을 합산하고, optionId 오름차순으로 정렬한다.
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
}
