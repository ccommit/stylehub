package ccommit.stylehub.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * PasswordHasher 의 검증 경계값을 실제 BCrypt 로 확인하는 단위 테스트이다.
 * 로그인 입력은 길이 제한이 없어 라이브러리 예외가 그대로 500 으로 새지 않는지를 고정한다.
 * </p>
 */
class PasswordHasherTest {

    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    @DisplayName("해싱한 비밀번호는 같은 평문과 일치하고 다른 평문과는 일치하지 않는다")
    void matchesOnlySamePassword() {
        String hashed = passwordHasher.hash("Test1234!");

        assertThat(passwordHasher.matches("Test1234!", hashed)).isTrue();
        assertThat(passwordHasher.matches("Wrong1234!", hashed)).isFalse();
    }

    @Test
    @DisplayName("72바이트를 넘는 비밀번호는 예외 없이 불일치로 처리한다")
    void returnsFalse_whenPasswordExceedsBcryptLimit() {
        String hashed = passwordHasher.hash("Test1234!");
        String tooLong = "a".repeat(73);

        assertThat(passwordHasher.matches(tooLong, hashed)).isFalse();
        assertThatCode(() -> passwordHasher.verifyDummy(tooLong)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("더미 검증은 결과를 돌려주지 않고 예외 없이 끝난다")
    void verifyDummy_completesWithoutException() {
        assertThatCode(() -> passwordHasher.verifyDummy("Test1234!")).doesNotThrowAnyException();
    }
}
