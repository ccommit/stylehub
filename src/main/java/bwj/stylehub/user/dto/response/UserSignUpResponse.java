package bwj.stylehub.user.dto.response;

import bwj.stylehub.user.entity.User;
import lombok.Builder;

import java.time.LocalDate;

@Builder
public record UserSignUpResponse(

        Long userId,
        String name,
        String email,
        LocalDate birthDate
) {
    public static UserSignUpResponse from(User user) {
        return UserSignUpResponse.builder()
                .userId(user.getUserId())
                .name(user.getName())
                .email(user.getEmail())
                .birthDate(user.getBirthDate())
                .build();
    }
}
