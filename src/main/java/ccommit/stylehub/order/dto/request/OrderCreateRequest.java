package ccommit.stylehub.order.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/08 by WonJin - refactor: OrderItemRequest → OrderDetailRequest 변경
 *
 * <p>
 * 주문 생성 요청 DTO이다.
 * 배송지 ID와 주문 항목 리스트를 전달한다.
 * </p>
 */
public record OrderCreateRequest(

        @NotNull(message = "배송지는 필수입니다")
        Long addressId,

        @NotEmpty(message = "주문 항목은 최소 1개 이상이어야 합니다")
        @Valid
        List<OrderDetailRequest> details
) {}
