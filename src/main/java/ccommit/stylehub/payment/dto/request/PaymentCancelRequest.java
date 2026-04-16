package ccommit.stylehub.payment.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * 결제 취소/부분 취소 요청 DTO이다.
 * cancelAmount가 null이면 전액 취소, 값이 있으면 부분 취소.
 * </p>
 */
public record PaymentCancelRequest(

        @NotBlank(message = "취소 사유는 필수입니다")
        String cancelReason,

        Integer cancelAmount
) {
}
