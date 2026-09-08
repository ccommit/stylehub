package ccommit.stylehub.payment.client;

import ccommit.stylehub.payment.dto.response.PgPaymentSnapshot;

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

    /**
     * 우리가 PG 에 넘긴 주문 식별자로 결제 현재 상태를 조회한다.
     * 승인 요청은 보냈으나 응답을 받지 못한 경우, PG 쪽 상태를 확인하는 유일한 수단이다.
     */
    PgPaymentSnapshot findPayment(String pgOrderId);

    // 팩토리에서 구현체를 식별하기 위한 PG사 타입
    String getType();
}
