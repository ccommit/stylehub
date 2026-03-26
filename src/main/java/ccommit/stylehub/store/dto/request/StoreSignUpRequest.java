package ccommit.stylehub.store.dto.request;

import static ccommit.stylehub.common.constants.ValidationPatterns.EMAIL_MESSAGE;
import static ccommit.stylehub.common.constants.ValidationPatterns.EMAIL_PATTERN;
import static ccommit.stylehub.common.constants.ValidationPatterns.NAME_MESSAGE;
import static ccommit.stylehub.common.constants.ValidationPatterns.NAME_PATTERN;
import static ccommit.stylehub.common.constants.ValidationPatterns.PASSWORD_MESSAGE;
import static ccommit.stylehub.common.constants.ValidationPatterns.PASSWORD_PATTERN;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * 스토어 회원가입 + 입점 신청을 동시에 처리하는 요청 DTO이다.
 * 회원 정보와 스토어 정보를 함께 전달한다.
 * </p>
 */
public record StoreSignUpRequest(

        // 회원 정보
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

        // 스토어 정보
        @NotBlank(message = "스토어명은 필수입니다")
        @Size(min = 2, max = 20, message = "스토어명은 2~20자여야 합니다")
        String storeName,

        @NotBlank(message = "스토어 설명은 필수입니다")
        @Size(max = 400, message = "스토어 설명은 400자 이내여야 합니다")
        String storeDescription
) {}
