package ccommit.stylehub.order.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/08 by WonJin - refactor: OrderItemRequest → OrderDetailRequest 변경
 * @modified 2026/05/08 by WonJin - feat: userCouponId 필드 추가 (optional, null 이면 쿠폰 미사용)
 *
 * <p>
 * 주문 생성 요청 DTO이다.
 * 배송지 ID, 주문 항목 리스트, *선택적* 쿠폰 ID 를 전달한다.
 * </p>
 */
public record OrderCreateRequest(

        @NotNull(message = "배송지는 필수입니다")
        Long addressId,

        @NotEmpty(message = "주문 항목은 최소 1개 이상이어야 합니다")
        @Valid
        List<OrderDetailRequest> details,

        // null 허용 — 쿠폰 미사용 주문
        Long userCouponId
) {}
