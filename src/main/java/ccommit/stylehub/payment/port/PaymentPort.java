package ccommit.stylehub.payment.port;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 * @modified 2026/04/22 by WonJin - refactor: Order 엔티티 직접 의존 제거, primitives로 시그니처 변경 (도메인 경계 누수 해소)
 * @modified 2026/09/08 by WonJin - feat: reconcileIfApproved 추가 — 만료 처리 직전 PG 결제 상태 대조
 * @modified 2026/09/17 by WonJin - fix: 대조 결과를 승인/진행 중/미승인 세 가지로 구분 (승인 진행 중인 주문을 만료 취소하지 않도록)
 *
 * <p>
 * Payment 도메인이 외부에 제공하는 포트 인터페이스이다.
 *
 * <p>주문과 결제 사이의 양방향 의존은 이벤트로 끊었지만, 주문 만료 처리에서 결제 상태를
 * 물어봐야 하는 경우처럼 답을 즉시 받아야 하는 조회는 이벤트로 표현할 수 없다.
 * 이 방향(order → payment) 은 단방향이므로 순환을 만들지 않는다.
 * </p>
 */
public interface PaymentPort {

    void createReady(Long orderId, int totalAmount, int finalAmount);

    // 만료 직전 PG 결제 상태를 대조해, 응답만 유실됐거나 PG에서 처리 중인 승인 결제가 취소되지 않게 한다.
    // 조회할 수 없다는 것은 승인되지 않았다는 것과 다르므로 조회 실패는 그대로 던진다.
    PaymentReconcileResult reconcileBeforeExpiry(Long orderId);
}
