package ccommit.stylehub.payment.port;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 주문 만료 처리 직전 PG 결제 상태 대조 결과이다.
 * 만료 처리 쪽이 결제 상태를 직접 해석하지 않고 "취소해도 되는가"만 판단할 수 있게 결론 세 가지로 좁혔다.
 * </p>
 */
public enum PaymentReconcileResult {

    // PG 기준 승인 완료(또는 이미 승인 반영됨). 주문을 취소하면 안 된다.
    APPROVED,

    // 승인 요청이 PG 에서 처리 중일 수 있다. 결론을 미루고 나중에 다시 대조한다.
    IN_FLIGHT,

    // 승인되지 않았고 결제를 만료 처리했다. 주문을 취소해도 된다.
    NOT_APPROVED
}
