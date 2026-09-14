package ccommit.stylehub.payment.port;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 * @modified 2026/04/22 by WonJin - refactor: Order 엔티티 직접 의존 제거, primitives로 시그니처 변경 (도메인 경계 누수 해소)
 * @modified 2026/09/08 by WonJin - feat: reconcileIfApproved 추가 — 만료 처리 직전 PG 결제 상태 대조
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

    /**
     * 만료 처리 직전에 PG 쪽 결제 상태를 조회해, 이미 승인됐다면 우리 상태를 맞춘다.
     *
     * <p>승인 요청은 PG 에 도달했는데 응답만 유실된 경우, 우리 DB 에는 결제 대기로 남는다.
     * 이 상태로 만료 시간이 지나면 사용자는 결제했는데 주문은 취소되는 최악의 결과가 된다.
     * 취소하기 전에 한 번 확인해 그 경우를 걸러낸다.
     *
     * @return true 면 PG 기준 승인 완료여서 우리 상태를 맞췄으므로 취소하면 안 된다
     */
    boolean reconcileIfApproved(Long orderId);
}
