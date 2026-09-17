package ccommit.stylehub.payment.dto.response;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 토스 실패 콜백(failUrl) 처리 결과 응답이다.
 * 인증 없이 열린 경로라 요청 파라미터(message 등)를 응답에 되돌려 쓰지 않고, 고정 안내 문구만 담는다.
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
