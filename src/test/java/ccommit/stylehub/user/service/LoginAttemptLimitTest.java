package ccommit.stylehub.user.service;

import ccommit.stylehub.common.config.PasswordHasher;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.support.OrderFixtureFactory;
import ccommit.stylehub.user.dto.request.UserLoginRequest;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 로그인 실패가 이메일·IP 별 한도를 넘으면 비밀번호 검증(BCrypt) 전에 429 로 거절하는지 실제 Redis 로 검증한다.
 * </p>
 */
@SpringBootTest
class LoginAttemptLimitTest {

    private static final String PASSWORD = "Test1234!";

    @Autowired
    private UserService userService;

    @Autowired
    private OrderFixtureFactory fixtureFactory;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private PasswordHasher passwordHasher;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<String> usedKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(usedKeys);
        fixtureFactory.deleteByIds(List.of(), List.of(), createdUserIds);
    }

    @Test
    @DisplayName("같은 이메일로 10번 실패하면 올바른 비밀번호로도 BCrypt 없이 429 로 거절한다")
    void blocksEmail_afterTooManyFailures() {
        // given
        User user = signUp();
        String ip = uniqueIp();
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES_PER_EMAIL; i++) {
            assertLoginFails(user.getEmail(), "wrong-password", ip, ErrorCode.INVALID_PASSWORD);
        }
        clearInvocations(passwordHasher);

        // when & then
        assertLoginFails(user.getEmail(), PASSWORD, uniqueIp(), ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
        verify(passwordHasher, never()).matches(anyString(), anyString());
        verify(passwordHasher, never()).verifyDummy(any());
    }

    @Test
    @DisplayName("로그인에 성공하면 그 이메일의 실패 횟수가 초기화된다")
    void resetsEmailFailures_onSuccess() {
        // given
        User user = signUp();
        String ip = uniqueIp();
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES_PER_EMAIL - 1; i++) {
            assertLoginFails(user.getEmail(), "wrong-password", ip, ErrorCode.INVALID_PASSWORD);
        }

        // when
        userService.login(new UserLoginRequest(user.getEmail(), PASSWORD), ip);

        // then
        assertLoginFails(user.getEmail(), "wrong-password", ip, ErrorCode.INVALID_PASSWORD);
        assertThat(redisTemplate.opsForValue().get(LoginAttemptLimiter.emailKey(user.getEmail()))).isEqualTo("1");
    }

    @Test
    @DisplayName("한 IP 가 여러 이메일을 번갈아 30번 실패하면, 그 IP 의 다음 요청은 새 이메일이어도 429 로 거절한다")
    void blocksIp_afterTooManyFailuresAcrossEmails() {
        // given
        String ip = uniqueIp();
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES_PER_IP; i++) {
            assertLoginFails(randomEmail(), "wrong-password", ip, ErrorCode.INVALID_PASSWORD);
        }

        // when & then
        assertLoginFails(randomEmail(), "wrong-password", ip, ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
        assertLoginFails(randomEmail(), "wrong-password", uniqueIp(), ErrorCode.INVALID_PASSWORD);
    }

    private void assertLoginFails(String email, String password, String ip, ErrorCode expected) {
        usedKeys.add(LoginAttemptLimiter.emailKey(email));
        usedKeys.add(LoginAttemptLimiter.ipKey(ip));
        assertThatThrownBy(() -> userService.login(new UserLoginRequest(email, password), ip))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", expected);
    }

    private User signUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = userService.signUp("제한테스트-" + unique, "limit-" + unique + "@test.com", PASSWORD,
                LocalDate.of(1996, 1, 1), UserRole.USER);
        createdUserIds.add(user.getUserId());
        return user;
    }

    private static String randomEmail() {
        return "nobody-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
    }

    // 테스트끼리 IP 실패 횟수가 섞이지 않게 매번 다른 주소를 쓴다.
    private static String uniqueIp() {
        return "10.0." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
    }
}
