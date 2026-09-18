package ccommit.stylehub.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 * @modified 2026/09/17 by WonJin - fix: 부분 취소 금액 0·음수 입력 차단
 *
 * <p>
 * 결제 취소/부분 취소 요청 DTO이다.
 * cancelAmount가 null이면 전액 취소, 값이 있으면 부분 취소.
 * </p>
 */
public record PaymentCancelRequest(

        @NotBlank(message = "취소 사유는 필수입니다")
        String cancelReason,

        @Positive(message = "취소 금액은 1원 이상이어야 합니다")
        Integer cancelAmount
) {
}
