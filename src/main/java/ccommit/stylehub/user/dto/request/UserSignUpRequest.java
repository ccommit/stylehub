package ccommit.stylehub.user.dto.request;

import static ccommit.stylehub.common.constants.ValidationPatterns.EMAIL_MESSAGE;
import static ccommit.stylehub.common.constants.ValidationPatterns.EMAIL_PATTERN;
import static ccommit.stylehub.common.constants.ValidationPatterns.NAME_MESSAGE;
import static ccommit.stylehub.common.constants.ValidationPatterns.NAME_PATTERN;
import static ccommit.stylehub.common.constants.ValidationPatterns.PASSWORD_MESSAGE;
import static ccommit.stylehub.common.constants.ValidationPatterns.PASSWORD_PATTERN;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 회원가입 요청 데이터를 담는 불변 DTO이다.
 * </p>
 */

public record UserSignUpRequest(

        @NotBlank
        @Size(min = 2, max = 10)
        @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
        String name,

        @NotBlank
        @Email(regexp = EMAIL_PATTERN, message = EMAIL_MESSAGE)
        String email,

        @NotBlank
        @Size(min = 8, max = 15)
        @Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_MESSAGE)
        String password,

        @NotNull
        @Past(message = "생년월일은 과거 날짜여야 합니다")
        LocalDate birthDate
) {
}
