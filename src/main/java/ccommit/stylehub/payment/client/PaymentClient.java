package ccommit.stylehub.payment.client;

/**
 * @author WonJin Bae
 * @created 2026/04/01
 *
 * <p>
 * PG사 결제 승인 API의 공통 인터페이스이다.
 * 전략 패턴으로 PG사별 구현체를 교체할 수 있다.
 * </p>
 */
public interface PaymentClient {

    void confirmPayment(String paymentKey, String orderId, Integer amount);

    void cancelPayment(String paymentKey, String cancelReason, Integer cancelAmount);

    // 팩토리에서 구현체를 식별하기 위한 PG사 타입
    String getType();
}
