package ccommit.stylehub.user.dto.response;

import ccommit.stylehub.user.entity.User;
import lombok.Builder;

import java.time.LocalDate;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 회원가입 결과를 클라이언트에 전달한다.
 * </p>
 */

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
