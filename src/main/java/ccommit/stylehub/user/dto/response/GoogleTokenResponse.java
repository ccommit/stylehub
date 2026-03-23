package ccommit.stylehub.user.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 구글 OAuth 토큰 교환 API의 응답을 매핑한다.
 * </p>
 */

public record GoogleTokenResponse(

        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Integer expiresIn
) {
}
