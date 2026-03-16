package bwj.stylehub.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UserSignUpRequest(

        @NotBlank
        @Size(min = 2, max = 10)
        @Pattern(regexp = "^[가-힣a-zA-Z0-9]+$", message = "한글, 알파벳, 숫자만 허용됩니다")
        String name,

        @NotBlank
        @Email(regexp = ".+@.+\\..+", message = "이메일 형식이 올바르지 않습니다")
        String email,

        @NotBlank
        @Size(min = 8, max = 15)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,15}$",
                message = "비밀번호는 8~15자이며, 영문·숫자·특수문자(@$!%*?&)를 각각 1개 이상 포함해야 합니다")
        String password,

        @NotNull
        @Past(message = "생년월일은 과거 날짜여야 합니다")
        LocalDate birthDate
) {
}
