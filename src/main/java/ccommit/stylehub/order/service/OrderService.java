package ccommit.stylehub.order.service;

import ccommit.stylehub.common.aop.ExecutionTimeCheck;
import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.coupon.dto.CouponUsageResult;
import ccommit.stylehub.coupon.entity.UserCoupon;
import ccommit.stylehub.coupon.port.CouponPort;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.OrderDetailRequest;
import ccommit.stylehub.order.dto.request.UpdateDeliveryStatusRequest;
import ccommit.stylehub.order.dto.response.OrderDetailResponse;
import ccommit.stylehub.order.dto.response.OrderListResponse;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.dto.response.OrderTotalAmountDto;
import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.OrderDetail;
import ccommit.stylehub.order.event.OrderPlacedEvent;
import ccommit.stylehub.product.port.ProductPort;
import ccommit.stylehub.user.port.UserPort;
import ccommit.stylehub.order.repository.OrderDetailRepository;
import ccommit.stylehub.order.repository.OrderQueryRepository;
import ccommit.stylehub.order.repository.OrderRepository;
import ccommit.stylehub.order.validator.DeliveryValidator;
import ccommit.stylehub.product.entity.ProductOption;
import ccommit.stylehub.user.entity.Address;
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

 * @modified 2026/04/02 by WonJin - feat: 배송 상태 변경 메서드 추가
 * @modified 2026/04/16 by WonJin - refactor: DeliveryStatus를 OrderStatus로 통합
 * @modified 2026/04/08 by WonJin - refactor: OrderItem → OrderDetail 변경
 * @modified 2026/04/08 by WonJin - refactor: 이벤트 발행 제거, Payment 직접 생성 + TransactionSynchronization으로 Redis 타임아웃 등록
 * @modified 2026/04/22 by WonJin - refactor: PaymentPort/OrderPaymentTimeout 직접 의존 제거, OrderPlacedEvent 발행으로 전환 (순환 참조 해소)
 * @modified 2026/04/22 by WonJin - refactor: cancelOrder/cancelPaidOrder 단일화 (Order 내부 상태 누수 제거)
 * @modified 2026/05/08 by WonJin - feat: 쿠폰 사용 주문 + 보상 트랜잭션 — placeOrder 가 CouponPort.useUserCoupon (비관적 락 + 검증 + 할인 + USED 전이) 호출, cancelOrder 에 restoreUserCoupon 추가 (결제 실패 시 UNUSED 복구). 시나리오 2-2 측정 위한 구현.
 * @modified 2026/09/17 by WonJin - fix: cancelUnpaidOrder 추가 — 만료·결제 실패 처리는 결제 대기 주문만 취소 (이미 결제된 주문을 환불 없이 취소하던 경로 차단)
 * @modified 2026/09/17 by WonJin - fix: 결제 후 취소를 cancelPaidOrder 로 분리(배송 준비·배송 완료 주문 포함), 배송 상태 변경 시 주문 행 락
 * @modified 2026/09/17 by WonJin - fix: 쿠폰 할인 기준으로 스토어별 주문 금액 전달 (스토어 쿠폰이 다른 스토어 상품까지 할인하던 문제)
 *
 * <p>
 * 주문 생성, 취소, 배송 상태 관리, 조회를 담당한다.
 * 주문/결제 API는 ApiLoggingAspect에 의해 요청/응답이 자동 로깅된다.
 * Payment 도메인과는 ApplicationEventPublisher를 통해서만 통신해 순환 의존성을 제거했다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderDetailRepository orderDetailRepository;
    private final OrderQueryRepository orderQueryRepository;
    private final DeliveryValidator deliveryValidator;
    private final UserPort userPort;
    private final ProductPort productPort;
    private final CouponPort couponPort;
    private final ApplicationEventPublisher eventPublisher;

    // 주문 생성·재고 차감·결제 READY 저장은 한 트랜잭션에서 하고, 커밋 후 Redis 타임아웃(10분)이 등록된다
    @ExecutionTimeCheck(threshold = 3000)
    @Transactional
    public OrderResponse placeOrder(Long userId, OrderCreateRequest request) {
        Address address = userPort.findAddressByOwner(userId, request.addressId());

        Order savedOrder = orderRepository.save(Order.create(address.getUser(), address));
        UserCoupon appliedCoupon = null;
        List<OrderDetail> savedDetails = decreaseStockAndCreateDetails(savedOrder, request.details(), appliedCoupon);

        int totalAmount = savedDetails.stream()
                .mapToInt(OrderDetail::getTotalPrice)
                .sum();

        // 쿠폰은 비관적 락으로 동시 사용을 막고 USED로 바꾸며, 결제 실패·주문 취소 시 UNUSED로 복구된다.
        // 할인 기준 금액은 쿠폰 유형(플랫폼/스토어)에 따라 쿠폰 도메인이 고르도록 스토어별 금액을 넘긴다.
        if (request.userCouponId() != null) {
            CouponUsageResult usage = couponPort.useUserCoupon(
                    userId, request.userCouponId(), amountByStore(savedDetails));
            savedOrder.applyDiscount(usage.discountAmount());
            // OrderDetail 의 첫 번째 항목에 userCoupon 연결 (cancelOrder 보상 시 추적용)
            if (!savedDetails.isEmpty()) {
                savedDetails.get(0).attachCoupon(usage.userCoupon());
            }
        }

        // TODO: 포인트 차감 처리 (User.pointBalance 차감 + PointHistory 기록)

        int finalAmount = savedOrder.calculateFinalAmount(totalAmount);

        eventPublisher.publishEvent(new OrderPlacedEvent(savedOrder.getOrderId(), totalAmount, finalAmount));

        return buildOrderResponse(savedOrder, savedDetails);
    }

    // 결제 취소 트랜잭션에 참여하므로 여기서 거절되면 PG 호출 전에 전체가 롤백된다.
    // 만료 처리가 이 경로를 타면 결제된 주문을 환불 없이 취소하게 되므로 결제 전 취소(cancelUnpaidOrder)와 나눈다.
    @Transactional
    public void cancelPaidOrder(Long orderId) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        order.cancelPaid();
        restoreStockAndCoupon(order);
    }

    // 주문 행을 잠근 뒤 상태를 보므로, 승인 반영이 먼저 커밋됐으면 PAID를 보고 아무것도 하지 않는다. 중복 호출에도 멱등하다.
    @Transactional
    public boolean cancelUnpaidOrder(Long orderId) {
        Order order = orderRepository.findByIdWithLock(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.isAwaitingPayment()) {
            return false;
        }
        order.cancelUnpaid();
        restoreStockAndCoupon(order);
        return true;
    }

    private void restoreStockAndCoupon(Order order) {
        restoreStock(order.getOrderId());
        restoreUserCoupon(order.getOrderId());  // 쿠폰 사용 주문이라면 UserCoupon UNUSED 로 복구 (보상)
    }

    // placeOrder가 쿠폰을 첫 주문 항목에만 연결하므로, 거기서 찾아 UNUSED로 복구한다
    private void restoreUserCoupon(Long orderId) {
        List<OrderDetail> details = orderDetailRepository.findByOrderIdWithDetails(orderId);
        details.stream()
                .map(OrderDetail::getUserCoupon)
                .filter(uc -> uc != null)
                .findFirst()
                .ifPresent(uc -> couponPort.restoreUserCoupon(uc.getUserCouponId()));
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
    // 결제 취소와 동시에 들어오면 취소된 주문을 배송 중으로 덮어쓸 수 있어 주문 행을 잠그고 검증한다.
    @Transactional
    public void updateDeliveryStatus(UpdateDeliveryStatusRequest request) {
        Order order = orderRepository.findByIdWithLock(request.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        deliveryValidator.validate(request, order);
        order.updateOrderStatus(request.newStatus());
    }

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

    // deadlock 방지를 위해 optionId 오름차순으로 락을 획득한다.
    private List<OrderDetail> decreaseStockAndCreateDetails(Order order, List<OrderDetailRequest> detailRequests, UserCoupon userCoupon) {
        List<OrderDetailRequest> merged = mergeAndSort(detailRequests);

        List<OrderDetail> details = new ArrayList<>(merged.size());
        for (OrderDetailRequest request : merged) {
            ProductOption option = productPort.decreaseStockWithLock(
                    request.productOptionId(), request.quantity()
            );

            details.add(OrderDetail.create(
                    option, order, request.quantity(),
                    option.getProductPrice(), userCoupon
            ));
        }

        return orderDetailRepository.saveAll(details);
    }

    private Map<Long, Integer> amountByStore(List<OrderDetail> details) {
        Map<Long, Integer> amounts = new HashMap<>();
        for (OrderDetail detail : details) {
            amounts.merge(detail.getProductOption().getStoreId(), detail.getTotalPrice(), Integer::sum);
        }
        return amounts;
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
