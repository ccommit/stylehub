package ccommit.stylehub.order.service;

import ccommit.stylehub.common.aop.ExecutionTimeCheck;
import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.request.UpdateDeliveryStatusRequest;
import ccommit.stylehub.order.dto.response.OrderDetailResponse;
import ccommit.stylehub.order.dto.response.OrderListResponse;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.dto.response.OrderTotalAmountDto;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderDetail;
import ccommit.stylehub.order.port.OrderPort;
import ccommit.stylehub.payment.port.PaymentPort;
import ccommit.stylehub.product.port.ProductPort;
import ccommit.stylehub.user.port.UserPort;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderQueryRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.scheduler.OrderPaymentTimeout;
import ccommit.stylehub.order.validator.DeliveryValidator;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.user.entity.Address;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/03/29 by WonJin - refactor: OrderTransactionService, OrderViewService를 OrderService로 통합

 * @modified 2026/04/02 by WonJin - feat: 배송 상태 변경 메서드 추가
 * @modified 2026/04/16 by WonJin - refactor: DeliveryStatus를 OrderStatus로 통합
 * @modified 2026/04/08 by WonJin - refactor: OrderItem → OrderDetail 변경
 * @modified 2026/04/08 by WonJin - refactor: 이벤트 발행 제거, Payment 직접 생성 + TransactionSynchronization으로 Redis 타임아웃 등록
 *
 * <p>
 * 주문 생성, 취소, 배송 상태 관리, 조회를 담당한다.
 * 주문/결제 API는 ApiLoggingAspect에 의해 요청/응답이 자동 로깅된다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class OrderService implements OrderPort {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderDetailRepository orderDetailRepository;
    private final OrderQueryRepository orderQueryRepository;
    private final DeliveryValidator deliveryValidator;
    private final OrderPaymentTimeout orderPaymentTimeout;
    private final UserPort userPort;
    private final ProductPort productPort;
    private final PaymentPort paymentPort;

    /**
     * 주문을 접수한다.
     * Order.create()는 엔티티 객체 생성, placeOrder()는 주문 접수 비즈니스 흐름을 담당한다.
     * 1. 주문 생성 + 재고 차감 + Payment READY 상태로 저장
     * 2. 트랜잭션 커밋 후: Redis 타임아웃 등록 (10분)
     * 3. 프론트(샌드박스)에서 pgOrderId + totalAmount로 토스 결제창 진입
     */
    @ExecutionTimeCheck(threshold = 3000)
    @Transactional
    public OrderResponse placeOrder(Long userId, OrderCreateRequest request) {
        Address address = userPort.findAddressByOwner(userId, request.addressId());

        // TODO: 쿠폰 유효성 검증 + 할인 금액 계산
        // TODO: 포인트 잔액 확인 + 차감

        Order savedOrder = orderRepository.save(Order.create(address.getUser(), address));
        List<OrderDetail> savedDetails = decreaseStockAndCreateDetails(savedOrder, request.details());

        // TODO: 쿠폰 사용 처리 (UserCoupon 상태 변경)
        // TODO: 포인트 차감 처리 (User.pointBalance 차감 + PointHistory 기록)

        int totalAmount = savedDetails.stream()
                .mapToInt(OrderDetail::getTotalPrice)
                .sum();
        int finalAmount = savedOrder.calculateFinalAmount(totalAmount);

        paymentPort.createReady(savedOrder, totalAmount, finalAmount);

        registerTimeoutAfterCommit(savedOrder.getOrderId());

        return buildOrderResponse(savedOrder, savedDetails);
    }

    private void registerTimeoutAfterCommit(Long orderId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                orderPaymentTimeout.registerTimeout(orderId);
            }
        });
    }

    /**
     * PENDING 상태인 주문을 취소하고 재고를 복구한다.
     * 상태 검증(cancel)을 먼저 수행하여 PENDING이 아닌 주문의 재고가 변경되는 것을 방지한다.
     */
    @Override
    @Transactional
    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        order.cancel();
        restoreStock(orderId);
    }

    @Override
    public void cancelPaidOrder(Long orderId) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        order.cancelPaid();
        restoreStock(orderId);
    }

    @Override
    public void removeTimeout(Long orderId) {
        orderPaymentTimeout.removeTimeout(orderId);
    }

    private void restoreStock(Long orderId) {
        List<OrderDetail> details = orderDetailRepository.findByOrderIdWithDetails(orderId);

        // deadlock 방지를 위해 optionId 오름차순으로 락을 획득한다.
        details.sort((a, b) -> Long.compare(
                a.getProductOption().getProductOptionId(),
                b.getProductOption().getProductOptionId()));

        for (OrderDetail detail : details) {
            productPort.increaseStock(
                    detail.getProductOption().getProductOptionId(),
                    detail.getQuantity()
            );
        }
    }

    // 배송 상태를 변경한다. 모든 검증은 DeliveryValidator에 위임한다.
    @Transactional
    public void updateDeliveryStatus(UpdateDeliveryStatusRequest request) {
        Order order = orderRepository.findById(request.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        deliveryValidator.validate(request, order);
        order.updateOrderStatus(request.newStatus());
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
        List<OrderDetail> details = orderDetailRepository.findByOrderIdWithDetails(orderId);
        return buildOrderResponse(order, details);
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
    private List<OrderDetail> decreaseStockAndCreateDetails(Order order, List<OrderDetailRequest> detailRequests) {
        List<OrderDetailRequest> merged = mergeAndSort(detailRequests);

        List<OrderDetail> details = new ArrayList<>(merged.size());
        for (OrderDetailRequest request : merged) {
            ProductOption option = productPort.decreaseStockWithLock(
                    request.productOptionId(), request.quantity()
            );

            // TODO: 쿠폰 기능 구현 시 UserCoupon 전달
            UserCoupon noCoupon = null;
            details.add(OrderDetail.create(
                    option, order, request.quantity(),
                    option.getProductPrice(), noCoupon
            ));
        }

        return orderDetailRepository.saveAll(details);
    }

    private List<OrderDetailRequest> mergeAndSort(List<OrderDetailRequest> detailRequests) {
        Map<Long, Integer> merged = new TreeMap<>();
        for (OrderDetailRequest request : detailRequests) {
            merged.merge(request.productOptionId(), request.quantity(), Integer::sum);
        }

        List<OrderDetailRequest> result = new ArrayList<>(merged.size());
        for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
            result.add(new OrderDetailRequest(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private OrderResponse buildOrderResponse(Order order, List<OrderDetail> details) {
        List<OrderDetailResponse> detailResponses = details.stream()
                .map(OrderDetailResponse::from)
                .toList();

        int totalAmount = details.stream()
                .mapToInt(OrderDetail::getTotalPrice)
                .sum();

        return OrderResponse.from(order, detailResponses, totalAmount, order.calculateFinalAmount(totalAmount));
    }

    private Map<Long, Integer> getTotalAmountMap(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> map = new HashMap<>(orderIds.size());
        for (OrderTotalAmountDto dto : orderDetailRepository.calculateTotalAmounts(orderIds)) {
            map.put(dto.orderId(), dto.totalAmount().intValue());
        }
        return map;
    }
}
