package ccommit.stylehub.user.dto.response;

import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import lombok.Builder;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * OAuth 소셜 로그인 결과를 클라이언트에 전달한다.
 * newUser 필드로 신규 가입 여부를 구분한다.
 * </p>
 */

@Builder
public record OAuthLoginResponse(

        Long userId,
        String name,
        String email,
        UserRole role,
        boolean newUser
) {
    public static OAuthLoginResponse from(User user, boolean newUser) {
        return OAuthLoginResponse.builder()
                .userId(user.getUserId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .newUser(newUser)
                .build();
    }
}
