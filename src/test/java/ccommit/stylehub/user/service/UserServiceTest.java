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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * @author WonJin Bae
 * @created 2026/04/24
 * @modified 2026/05/01 by WonJin - test: throws_whenEmailNotFound DisplayName 에 user enumeration 방지 의도 명시 — 코드 리뷰 시 INVALID_PASSWORD 반환이 버그처럼 보이지 않도록
 * @modified 2026/09/17 by WonJin - test: 미존재 이메일·소셜 계정·비활성 계정이 INVALID_PASSWORD 로 응답하고 더미 해시 검증을 수행하는지 검증 추가
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
            given(user.getActive()).willReturn(true);
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
            given(storeUser.getActive()).willReturn(true);
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
        @DisplayName("보안상 이메일 존재 여부를 노출하지 않기 위해 미존재 이메일에도 BusinessException(INVALID_PASSWORD) 을 던진다 (user enumeration 방지)")
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
            // 응답 시간으로 가입 여부가 드러나지 않도록 계정이 없어도 BCrypt 검증을 한 번 수행한다
            then(passwordHasher).should().verifyDummy("raw-pw");
            then(passwordHasher).should(never()).matches(any(), any());
        }

        @Test
        @DisplayName("비밀번호가 없는 소셜 가입 계정은 NPE(500) 대신 INVALID_PASSWORD 로 응답하고 더미 검증을 수행한다")
        void throws_whenSocialAccountHasNoPassword() {
            // given — 구글로 가입한 회원은 password 가 null 이다
            UserLoginRequest request = new UserLoginRequest("social@test.com", "raw-pw");
            User socialUser = mock(User.class);
            given(socialUser.getPassword()).willReturn(null);
            given(socialUser.getActive()).willReturn(true);

            stubTransactionTemplatePassthrough();
            given(userRepository.findByEmail("social@test.com")).willReturn(Optional.of(socialUser));

            // when / then
            assertThatThrownBy(() -> userService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
            then(passwordHasher).should().verifyDummy("raw-pw");
            then(passwordHasher).should(never()).matches(any(), any());
            then(userRepository).should(never()).findById(any());   // 포인트 적립으로 넘어가지 않는다
        }

        @Test
        @DisplayName("비활성 계정은 비밀번호가 맞아도 INVALID_PASSWORD 로 응답하고 더미 검증을 수행한다")
        void throws_whenAccountInactive() {
            // given
            UserLoginRequest request = new UserLoginRequest("inactive@test.com", "raw-pw");
            User inactiveUser = mock(User.class);
            given(inactiveUser.getPassword()).willReturn("hashed-pw");
            given(inactiveUser.getActive()).willReturn(false);

            stubTransactionTemplatePassthrough();
            given(userRepository.findByEmail("inactive@test.com")).willReturn(Optional.of(inactiveUser));
            given(passwordHasher.matches("raw-pw", "hashed-pw")).willReturn(true);

            // when / then
            assertThatThrownBy(() -> userService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
            then(passwordHasher).should().verifyDummy("raw-pw");
            then(passwordHasher).should(never()).matches(any(), any());
            then(userRepository).should(never()).findById(any());
        }

        @Test
        @DisplayName("비밀번호가 일치하지 않으면 BusinessException(INVALID_PASSWORD) 을 던진다")
        void throws_whenPasswordMismatch() {
            // given
            UserLoginRequest request = new UserLoginRequest("user@test.com", "wrong-pw");
            User user = mock(User.class);
            given(user.getPassword()).willReturn("hashed-pw");
            given(user.getActive()).willReturn(true);

            stubTransactionTemplatePassthrough();
            given(userRepository.findByEmail("user@test.com")).willReturn(Optional.of(user));
            given(passwordHasher.matches("wrong-pw", "hashed-pw")).willReturn(false);

            // when / then
            assertThatThrownBy(() -> userService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_PASSWORD);
            then(passwordHasher).should(never()).verifyDummy(any());   // 실제 검증을 했으므로 더미 검증은 중복하지 않는다
        }
    }

    // ===== Helper =====

    // executeWithoutResult도 스텁한다. default 메서드지만 목에서는 기본 구현이 호출되지 않아 콜백이 실행되지 않는다.
    private void stubTransactionTemplatePassthrough() {
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        willAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).given(transactionTemplate).executeWithoutResult(any());
    }
}
