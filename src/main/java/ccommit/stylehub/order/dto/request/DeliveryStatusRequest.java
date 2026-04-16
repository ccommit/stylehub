package ccommit.stylehub.order.dto.request;

import ccommit.stylehub.order.enums.OrderStatus;
import jakarta.validation.constraints.NotNull;

/**
 * @author WonJin Bae
 * @created 2026/04/02
 * @modified 2026/04/16 by WonJin - refactor: DeliveryStatus를 OrderStatus로 통합
 *
 * <p>
 * 배송 상태 변경 요청 DTO이다.
 * </p>
 */
public record DeliveryStatusRequest(

        @NotNull(message = "배송 상태는 필수입니다")
        OrderStatus orderStatus
) {
}
