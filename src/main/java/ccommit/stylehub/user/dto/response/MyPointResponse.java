package ccommit.stylehub.user.dto.response;

import ccommit.stylehub.common.dto.CursorResponse;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 보유 포인트 조회 응답 DTO이다. 현재 잔액과 최신순 이력 한 페이지를 함께 돌려준다.
 * 이력은 커서 페이징이라 다음 페이지 요청에서도 잔액이 함께 오며, 항상 요청 시점의 잔액이다.
 * </p>
 */
public record MyPointResponse(
        int pointBalance,
        CursorResponse<PointHistoryResponse> histories
) {
}
