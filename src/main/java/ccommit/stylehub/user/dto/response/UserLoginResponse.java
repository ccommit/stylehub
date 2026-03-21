package ccommit.stylehub.user.dto.response;

import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.Role;
import lombok.Builder;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 일반 로그인 결과를 클라이언트에 전달한다.
 * </p>
 */

@Builder
public record UserLoginResponse(

        Long userId,
        String name,
        String email,
        Role role
) {
    public static UserLoginResponse from(User user) {
        return UserLoginResponse.builder()
                .userId(user.getUserId())
                .name(user.getName())
                .email(user.getEmail())
                .role(user.getRole())
                .build();
    }
}
