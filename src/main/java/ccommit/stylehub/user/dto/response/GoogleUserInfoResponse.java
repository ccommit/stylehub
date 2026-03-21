package ccommit.stylehub.user.dto.response;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 구글 사용자 정보 API의 응답을 매핑한다.
 * </p>
 */

public record GoogleUserInfoResponse(

        String sub,
        String name,
        String email
) {
}
