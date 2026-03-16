package bwj.stylehub.user.dto.response;

import bwj.stylehub.user.entity.User;
import bwj.stylehub.user.enums.Role;
import lombok.Builder;

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
