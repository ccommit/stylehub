package ccommit.stylehub.user.service;

import ccommit.stylehub.user.dto.response.OAuthLoginResponse;
import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.OAuthProvider;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author WonJin Bae
 * @created 2026/08/27
 *
 * <p>
 * OAuthService의 소셜 로그인/신규 가입/동시 가입 경합 처리 로직을 검증하는 단위테스트이다.
 * OAuthClient는 Mock으로 대체하고, TransactionTemplate은 콜백을 즉시 실행하도록 Stub 처리한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class OAuthServiceTest {

    @Mock
    private OAuthClient googleClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private TransactionTemplate transactionTemplate;

    private void stubTransactionTemplateToRunCallback() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private OAuthService newOAuthService() {
        when(googleClient.provider()).thenReturn(OAuthProvider.GOOGLE);
        return new OAuthService(List.of(googleClient), userRepository, userService, transactionTemplate);
    }

    private User userWithId(long userId, String name, String email, OAuthProvider provider) {
        User user = User.createOAuth(name, email, provider, "provider-id-1");
        ReflectionTestUtils.setField(user, "userId", userId);
        return user;
    }

    @Nested
    @DisplayName("getAuthorizationUrl")
    class GetAuthorizationUrl {

        @Test
        @DisplayName("등록된 Provider면 해당 클라이언트의 인증 URL을 반환한다")
        void 등록된_Provider면_URL을_반환한다() {
            // given
            OAuthService oAuthService = newOAuthService();
            when(googleClient.getAuthorizationUrl()).thenReturn("https://accounts.google.com/authorize");

            // when & then
            assertThat(oAuthService.getAuthorizationUrl(OAuthProvider.GOOGLE))
                    .isEqualTo("https://accounts.google.com/authorize");
        }

        @Test
        @DisplayName("등록되지 않은 Provider면 예외가 발생한다")
        void 등록되지_않은_Provider면_예외() {
            // given — 클라이언트를 하나도 등록하지 않은 OAuthService
            OAuthService oAuthService = new OAuthService(List.of(), userRepository, userService, transactionTemplate);

            // when & then
            assertThatThrownBy(() -> oAuthService.getAuthorizationUrl(OAuthProvider.GOOGLE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("지원하지 않는 OAuth OAuthProvider");
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("기존 OAuth 가입자면 재가입 없이 로그인 처리하고 포인트를 지급한다")
        void 기존_가입자는_로그인만_처리한다() {
            // given
            OAuthService oAuthService = newOAuthService();
            stubTransactionTemplateToRunCallback();
            OAuthUserInfo userInfo = new OAuthUserInfo("철수", "a@test.com", "provider-id-1");
            User existingUser = userWithId(1L, "철수", "a@test.com", OAuthProvider.GOOGLE);
            when(googleClient.authenticate("code")).thenReturn(userInfo);
            when(userRepository.findByEmail("a@test.com")).thenReturn(Optional.of(existingUser));

            // when
            OAuthLoginResponse response = oAuthService.login(OAuthProvider.GOOGLE, "code");

            // then
            assertThat(response.userId()).isEqualTo(1L);
            assertThat(response.newUser()).isFalse();
            verify(userService).rewardLoginPoint(eq(1L), any());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("이미 일반 회원가입으로 등록된 이메일이면 예외가 발생한다")
        void 일반가입_이메일이면_예외() {
            // given
            OAuthService oAuthService = newOAuthService();
            stubTransactionTemplateToRunCallback();
            OAuthUserInfo userInfo = new OAuthUserInfo("철수", "a@test.com", "provider-id-1");
            User generalUser = User.create("철수", "a@test.com", "HASHED", null, UserRole.USER); // provider == null
            when(googleClient.authenticate("code")).thenReturn(userInfo);
            when(userRepository.findByEmail("a@test.com")).thenReturn(Optional.of(generalUser));

            // when & then
            assertThatThrownBy(() -> oAuthService.login(OAuthProvider.GOOGLE, "code"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("이미 일반 회원가입으로 등록된 이메일입니다");
            verify(userService, never()).rewardLoginPoint(anyLong(), any());
        }

        @Test
        @DisplayName("신규 사용자면 회원가입 후 로그인 처리하고 포인트를 지급한다")
        void 신규_사용자는_가입_후_로그인_처리한다() {
            // given
            OAuthService oAuthService = newOAuthService();
            stubTransactionTemplateToRunCallback();
            OAuthUserInfo userInfo = new OAuthUserInfo("영희", "b@test.com", "provider-id-2");
            when(googleClient.authenticate("code")).thenReturn(userInfo);
            when(userRepository.findByEmail("b@test.com")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User saved = invocation.getArgument(0);
                ReflectionTestUtils.setField(saved, "userId", 2L);
                return saved;
            });

            // when
            OAuthLoginResponse response = oAuthService.login(OAuthProvider.GOOGLE, "code");

            // then
            assertThat(response.userId()).isEqualTo(2L);
            assertThat(response.newUser()).isTrue();
            verify(userService).rewardLoginPoint(eq(2L), any());
        }

        @Test
        @DisplayName("동시 가입 경합으로 유니크 제약이 깨지면 재조회해 기존 유저로 로그인 처리한다")
        void 동시가입_경합시_재조회하여_로그인_처리한다() {
            // given
            OAuthService oAuthService = newOAuthService();
            OAuthUserInfo userInfo = new OAuthUserInfo("영희", "b@test.com", "provider-id-2");
            User racedUser = userWithId(3L, "영희", "b@test.com", OAuthProvider.GOOGLE);
            when(googleClient.authenticate("code")).thenReturn(userInfo);
            // 최초 시도는 동시 INSERT로 인한 유니크 제약 위반, 재시도는 정상 실행
            when(transactionTemplate.execute(any()))
                    .thenThrow(new DataIntegrityViolationException("dup"))
                    .thenAnswer(invocation -> {
                        TransactionCallback<?> callback = invocation.getArgument(0);
                        return callback.doInTransaction(null);
                    });
            when(userRepository.findByEmail("b@test.com")).thenReturn(Optional.of(racedUser));

            // when
            OAuthLoginResponse response = oAuthService.login(OAuthProvider.GOOGLE, "code");

            // then
            assertThat(response.userId()).isEqualTo(3L);
            assertThat(response.newUser()).isFalse();
            verify(userService).rewardLoginPoint(eq(3L), any());
        }

        @Test
        @DisplayName("STORE/ADMIN 역할로 재조회된 사용자는 포인트를 지급받지 않는다")
        void STORE_역할은_포인트를_지급받지_않는다() {
            // given
            OAuthService oAuthService = newOAuthService();
            stubTransactionTemplateToRunCallback();
            OAuthUserInfo userInfo = new OAuthUserInfo("스토어주인", "s@test.com", "provider-id-3");
            User storeUser = User.builder()
                    .name("스토어주인")
                    .email("s@test.com")
                    .provider(OAuthProvider.GOOGLE)
                    .providerUserId("provider-id-3")
                    .role(UserRole.STORE)
                    .build();
            when(googleClient.authenticate("code")).thenReturn(userInfo);
            when(userRepository.findByEmail("s@test.com")).thenReturn(Optional.of(storeUser));

            // when
            oAuthService.login(OAuthProvider.GOOGLE, "code");

            // then
            verify(userService, never()).rewardLoginPoint(anyLong(), any());
        }
    }
}
