package bwj.stylehub.user.dto.response;

import bwj.stylehub.user.entity.User;
import bwj.stylehub.user.enums.Role;
import lombok.Builder;

@Builder
public record OAuthLoginResponse(

        Long userId,
        String name,
        String email,
        Role role,
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
