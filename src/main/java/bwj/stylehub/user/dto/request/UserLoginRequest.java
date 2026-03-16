package bwj.stylehub.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserLoginRequest(

        @NotBlank
        @Email(regexp = ".+@.+\\..+", message = "이메일 형식이 올바르지 않습니다")
        String email,

        @NotBlank
        @Size(min = 8, max = 15)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,15}$",
                message = "비밀번호는 8~15자이며, 영문·숫자·특수문자(@$!%*?&)를 각각 1개 이상 포함해야 합니다")
        String password
) {
}
