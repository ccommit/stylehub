package ccommit.stylehub.user.dto.request;

import static ccommit.stylehub.common.constants.ValidationPatterns.EMAIL_MESSAGE;
import static ccommit.stylehub.common.constants.ValidationPatterns.EMAIL_PATTERN;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 로그인 요청 데이터를 담는 불변 DTO이다.
 * </p>
 */

public record UserLoginRequest(

        @NotBlank
        @Email(regexp = EMAIL_PATTERN, message = EMAIL_MESSAGE)
        String email,

        @NotBlank
        String password
) {
}
