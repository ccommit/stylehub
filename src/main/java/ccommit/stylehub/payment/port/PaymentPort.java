package ccommit.stylehub.payment.port;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 * @modified 2026/04/22 by WonJin - refactor: Order 엔티티 직접 의존 제거, primitives로 시그니처 변경 (도메인 경계 누수 해소)
 *
 * <p>
 * Payment 도메인이 외부에 제공하는 포트 인터페이스이다.
 * 결제 대기 건 생성을 제공한다.
 * 이벤트 기반 리팩터링 이후 현재는 구현체가 없고 호출부도 이벤트로 통합되어 고아 상태이며, 후속 PR에서 제거 예정이다.
 * </p>
 */
public interface PaymentPort {

    void createReady(Long orderId, int totalAmount, int finalAmount);
}
