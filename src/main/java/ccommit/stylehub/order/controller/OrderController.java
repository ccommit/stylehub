package ccommit.stylehub.order.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.order.dto.request.DeliveryStatusRequest;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.request.UpdateDeliveryStatusRequest;
import ccommit.stylehub.order.dto.response.OrderCursorResponse;
import ccommit.stylehub.common.dto.CursorResponse;
import ccommit.stylehub.order.dto.response.OrderListResponse;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/02 by WonJin - refactor: 배송상태 API 추가
 * @modified 2026/04/16 by WonJin - refactor: DeliveryStatus를 OrderStatus로 통합
 *
 * <p>
 * 주문 관련 API를 제공한다.
 * USER API(주문 생성/조회)와 STORE API(배송 상태 변경)를 메서드별 역할로 구분한다.
 * </p>
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    //USER API
    @PostMapping("/orders")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.placeOrder(userId, request));
    }

    @GetMapping("/orders")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<OrderCursorResponse> getMyOrders(
    @GetMapping
    public ResponseEntity<CursorResponse<OrderListResponse>> getMyOrders(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(orderService.getMyOrders(userId, cursor, size));
    }

    @GetMapping("/orders/{orderId}")
    @RequiredRole(UserRole.USER)
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable Long orderId,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(orderService.getOrder(userId, orderId));
    }

    // STORE API (배송 상태 관리)
    @PatchMapping("/stores/{storeId}/orders/{orderId}/delivery")
    @RequiredRole(UserRole.STORE)
    public ResponseEntity<Void> updateDeliveryStatus(
            @PathVariable Long storeId,
            @PathVariable Long orderId,
            @Valid @RequestBody DeliveryStatusRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        orderService.updateDeliveryStatus(
                new UpdateDeliveryStatusRequest(userId, storeId, orderId, request.orderStatus()));
        return ResponseEntity.ok().build();
    }
}
