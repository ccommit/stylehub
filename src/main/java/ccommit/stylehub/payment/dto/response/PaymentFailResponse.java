package ccommit.stylehub.payment.dto.response;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 토스 실패 콜백 응답이다. 요청 파라미터를 되돌려 쓰지 않고 고정 문구만 담는다.
 * </p>
 */
public record PaymentFailResponse(
        String orderId,
        String message
) {

    private static final String DEFAULT_MESSAGE = "결제가 완료되지 않았습니다";

    public static PaymentFailResponse of(String pgOrderId) {
        return new PaymentFailResponse(pgOrderId, DEFAULT_MESSAGE);
    }
}
