package ccommit.stylehub.user.dto.response;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * OAuth 제공자별 사용자 정보를 통일된 형식으로 전달하는 내부 DTO이다.
 * </p>
 */

public record OAuthUserInfo(
        String name,
        String email,
        String providerId
) {
}
