package ccommit.stylehub.user.dto.response;

import ccommit.stylehub.user.enums.PointType;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 포인트 이력 한 건의 응답 DTO이다(일시, 유형, 변동 포인트, 잔여 포인트, 관련 주문).
 * amount 는 잔액에 더해진 값이라 적립·사용 취소는 양수, 사용은 음수다. USE 이면서 양수인 건은 주문 취소로 되돌린 사용 포인트다.
 * </p>
 */
public record PointHistoryResponse(
        Long pointId,
        PointType pointType,
        Integer amount,
        Integer balanceSnapshot,
        // 로그인 적립처럼 주문과 무관한 이력은 null
        Long orderId,
        LocalDateTime createdAt
) {
}
