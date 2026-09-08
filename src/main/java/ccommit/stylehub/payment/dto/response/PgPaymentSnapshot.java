package ccommit.stylehub.payment.dto.response;

/**
 * @author WonJin Bae
 * @created 2026/09/08
 *
 * <p>
 * PG 사에 조회한 결제의 현재 상태이다.
 *
 * <p>우리 DB 의 Payment 와는 별개로, "PG 쪽에서는 이 결제가 어떤 상태인가" 만 담는다.
 * 두 값이 어긋난 상황을 다루기 위한 DTO 이므로 우리 엔티티와 섞지 않는다.
 *
 * @param approved PG 기준 승인 완료 여부
 * @param paymentKey PG 가 발급한 결제 키. 승인 상태일 때만 의미가 있다
 * @param totalAmount PG 가 기록한 결제 금액
 * </p>
 */
public record PgPaymentSnapshot(
        boolean approved,
        String paymentKey,
        Integer totalAmount
) {

    /** PG 에 해당 주문의 결제 기록이 아예 없는 경우. 승인되지 않은 것으로 다룬다. */
    public static PgPaymentSnapshot notFound() {
        return new PgPaymentSnapshot(false, null, null);
    }
}
