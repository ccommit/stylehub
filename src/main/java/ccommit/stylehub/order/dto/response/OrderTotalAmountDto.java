package ccommit.stylehub.order.dto.response;

/**
 * @author WonJin Bae
 * @created 2026/03/29
 *
 * <p>
 * 주문별 총 금액을 담는 DTO이다.
 * JPQL의 new 키워드로 직접 매핑하여 Object[] 캐스팅을 제거한다.
 * </p>
 */
public record OrderTotalAmountDto(
        Long orderId,
        Long totalAmount
) {
}
