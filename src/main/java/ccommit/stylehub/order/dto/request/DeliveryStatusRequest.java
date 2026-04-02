package ccommit.stylehub.order.dto.request;

import ccommit.stylehub.order.enums.DeliveryStatus;
import jakarta.validation.constraints.NotNull;

/**
 * @author WonJin Bae
 * @created 2026/04/02
 *
 * <p>
 * 배송 상태 변경 요청 DTO이다.
 * </p>
 */
public record DeliveryStatusRequest(

        @NotNull(message = "배송 상태는 필수입니다")
        DeliveryStatus deliveryStatus
) {
}
