package ccommit.stylehub.order.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/08 by WonJin - refactor: OrderItemRequest → OrderDetailRequest 변경
 * @modified 2026/05/08 by WonJin - feat: userCouponId 필드 추가 (optional, null 이면 쿠폰 미사용)
 * @modified 2026/09/17 by WonJin - feat: usedPoint 필드 추가 (optional, null 이면 0, 음수 거절)
 *
 * <p>
 * 주문 생성 요청 DTO이다.
 * 배송지 ID, 주문 항목 리스트, *선택적* 쿠폰 ID 와 사용 포인트를 전달한다.
 * </p>
 */
public record OrderCreateRequest(

        @NotNull(message = "배송지는 필수입니다")
        Long addressId,

        @NotEmpty(message = "주문 항목은 최소 1개 이상이어야 합니다")
        @Valid
        List<OrderDetailRequest> details,

        // null 허용 — 쿠폰 미사용 주문
        Long userCouponId,

        // null 허용 — 포인트 미사용 주문(0 과 같다)
        @PositiveOrZero(message = "사용 포인트는 0 이상이어야 합니다")
        Integer usedPoint
) {

    // 포인트 필드 추가 전의 호출부가 "포인트 미사용"을 null 인자 없이 표현하게 한다. JSON 역직렬화는 레코드 정식 생성자를 쓴다.
    public OrderCreateRequest(Long addressId, List<OrderDetailRequest> details, Long userCouponId) {
        this(addressId, details, userCouponId, null);
    }

    public int usedPointOrZero() {
        return usedPoint == null ? 0 : usedPoint;
    }
}
