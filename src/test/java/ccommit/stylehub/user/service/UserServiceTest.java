package ccommit.stylehub.user.service;

import ccommit.stylehub.common.config.PasswordHasher;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.request.UserLoginRequest;
import ccommit.stylehub.user.dto.response.UserLoginResponse;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/04/24
 *
 * <p>
 * UserService.login 의 단위 테스트이다.
 * TransactionTemplate 은 콜백을 즉시 실행하도록 스텁해 트랜잭션 없이도 내부 로직이 그대로 동작하도록 한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private UserService userService;

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("정상 로그인(USER) 시 응답이 반환되고 로그인 포인트 보상이 호출된다")
        void returnsResponseAndRewardsPoint_whenUserLoginsSuccessfully() {
            // given
            UserLoginRequest request = new UserLoginRequest("user@test.com", "raw-pw");
            User user = mock(User.class);
            given(user.getUserId()).willReturn(1L);
            given(user.getName()).willReturn("테스터");
            given(user.getEmail()).willReturn("user@test.com");
            given(user.getPassword()).willReturn("hashed-pw");
            given(user.getRole()).willReturn(UserRole.USER);
            given(user.getLastLoginDate()).willReturn(null);   // 최초 로그인 → 1000P 지급 경로

            stubTransactionTemplatePassthrough();
            given(userRepository.findByEmail("user@test.com")).willReturn(Optional.of(user));
            given(passwordHasher.matches("raw-pw", "hashed-pw")).willReturn(true);
            given(userRepository.findById(1L)).willReturn(Optional.of(user));

            // when
            UserLoginResponse response = userService.login(request);

            // then
            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.email()).isEqualTo("user@test.com");
            assertThat(response.role()).isEqualTo(UserRole.USER);
            then(userRepository).should().findById(1L);        // rewardLoginPoint 내부 호출 확인
            then(user).should().addPoint(1000);                // 최초 로그인 포인트
            then(user).should().updateLastLoginDate(any());
        }

        @Test
        @DisplayName("정상 로그인(STORE) 시 응답은 반환되지만 로그인 포인트 보상은 호출되지 않는다")
        void skipsRewardPoint_whenStoreLogins() {
            // given
            UserLoginRequest request = new UserLoginRequest("store@test.com", "raw-pw");
            User storeUser = mock(User.class);
            given(storeUser.getUserId()).willReturn(2L);
            given(storeUser.getName()).willReturn("스토어");
            given(storeUser.getEmail()).willReturn("store@test.com");
            given(storeUser.getPassword()).willReturn("hashed-pw");
            given(storeUser.getRole()).willReturn(UserRole.STORE);

            stubTransactionTemplatePassthrough();
            given(userRepository.findByEmail("store@test.com")).willReturn(Optional.of(storeUser));
            given(passwordHasher.matches("raw-pw", "hashed-pw")).willReturn(true);

            // when
            UserLoginResponse response = userService.login(request);

            // then
            assertThat(response.role()).isEqualTo(UserRole.STORE);
            then(userRepository).should(never()).findById(any());    // rewardLoginPoint 진입 안 함
            then(storeUser).should(never()).addPoint(any(Integer.class));
        }

        @Test
        @DisplayName("존재하지 않는 이메일이면 BusinessException(INVALID_PASSWORD) 을 던진다")
        void throws_whenEmailNotFound() {
            // given
            UserLoginRequest request = new UserLoginRequest("none@test.com", "raw-pw");
            stubTransactionTemplatePassthrough();
            given(userRepository.findByEmail("none@test.com")).willReturn(Optional.empty());

            // when / then
            assertThatThrownBy(() -> userService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
        }

        @Test
        @DisplayName("비밀번호가 일치하지 않으면 BusinessException(INVALID_PASSWORD) 을 던진다")
        void throws_whenPasswordMismatch() {
            // given
            UserLoginRequest request = new UserLoginRequest("user@test.com", "wrong-pw");
            User user = mock(User.class);
            given(user.getPassword()).willReturn("hashed-pw");

            stubTransactionTemplatePassthrough();
            given(userRepository.findByEmail("user@test.com")).willReturn(Optional.of(user));
            given(passwordHasher.matches("wrong-pw", "hashed-pw")).willReturn(false);

            // when / then
            assertThatThrownBy(() -> userService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
        }
    }

    // ===== Helper =====

    /**
     * TransactionTemplate 를 "콜백을 즉시 실행하고 결과를 반환"하도록 스텁한다.
     * 실제 트랜잭션 없이 내부 조회/검증 로직을 그대로 검증할 수 있다.
     */
    private void stubTransactionTemplatePassthrough() {
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }
}
