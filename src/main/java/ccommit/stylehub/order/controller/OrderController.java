package ccommit.stylehub.order.controller;

import ccommit.stylehub.common.config.RequiredRole;
import ccommit.stylehub.common.util.SessionUtils;
import ccommit.stylehub.order.dto.request.OrderCreateRequest;
import ccommit.stylehub.order.dto.response.OrderCursorResponse;
import ccommit.stylehub.order.dto.response.OrderResponse;
import ccommit.stylehub.order.service.OrderService;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/03/29 by WonJin - refactor: OrderViewService를 OrderService로 통합
 *
 * <p>
 * 사용자의 주문 생성 및 주문 내역 조회 API를 제공한다.
 * </p>
 */
// TODO: 토스페이먼츠 결제 연동 시 결제 확인/취소 API 추가 예정
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@RequiredRole(UserRole.USER)
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.placeOrder(userId, request));
    }

    @GetMapping
    public ResponseEntity<OrderCursorResponse> getMyOrders(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(orderService.getMyOrders(userId, cursor, size));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable Long orderId,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(orderService.getOrder(userId, orderId));
    }
}
