package ccommit.stylehub.user.service;

import ccommit.stylehub.common.constants.ValidationPatterns;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.response.OAuthLoginResponse;
import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.OAuthProvider;
import ccommit.stylehub.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * OAuthService.login의 가입·복구 경로를 검증하는 단위 테스트이다.
 * 닉네임 생성기는 실제 구현을 써서 저장되는 닉네임 규칙까지 확인하고, TransactionTemplate은 콜백을 즉시 실행하도록 스텁한다.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthServiceTest {

    private static final String CODE = "auth-code";
    private static final OAuthUserInfo GOOGLE_USER = new OAuthUserInfo("홍길동", "hong@gmail.com", "google-sub-1");

    @Mock
    private OAuthClient oAuthClient;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private TransactionTemplate transactionTemplate;

    private OAuthService oAuthService;

    @BeforeEach
    void setUp() {
        // OAuthService 는 생성 시점에 provider() 로 클라이언트를 등록하므로 생성 전에 스텁한다
        given(oAuthClient.provider()).willReturn(OAuthProvider.GOOGLE);
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        oAuthService = new OAuthService(
                List.of(oAuthClient), userRepository, userService, transactionTemplate, new SocialNicknameGenerator()
        );
    }

    @Test
    @DisplayName("같은 닉네임이 이미 있으면 접미사를 붙인 닉네임으로 가입한다")
    void signsUpWithSuffix_whenNameTaken() {
        // given — 동명이인이 이미 가입해 있다
        given(oAuthClient.authenticate(CODE)).willReturn(GOOGLE_USER);
        given(userRepository.findByEmail(GOOGLE_USER.email())).willReturn(Optional.empty());
        given(userRepository.existsByName(anyString())).willAnswer(invocation -> "홍길동".equals(invocation.getArgument(0)));
        given(userRepository.save(any(User.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        OAuthLoginResponse response = oAuthService.login(OAuthProvider.GOOGLE, CODE);

        // then
        User saved = captureSavedUsers(1).get(0);
        assertThat(saved.getName()).startsWith("홍길동").isNotEqualTo("홍길동");
        assertValidNickname(saved.getName());
        assertThat(saved.getEmail()).isEqualTo(GOOGLE_USER.email());
        assertThat(response.newUser()).isTrue();
    }

    @Test
    @DisplayName("동시 가입으로 유니크 위반이 나고 재조회로 회원이 보이면 그 회원으로 로그인한다")
    void logsInExistingUser_whenConcurrentSignUpWithSameEmail() {
        // given — 같은 이메일의 다른 요청이 먼저 저장했다
        User alreadySaved = User.createOAuth("홍길동", GOOGLE_USER.email(), OAuthProvider.GOOGLE, GOOGLE_USER.providerId());
        given(oAuthClient.authenticate(CODE)).willReturn(GOOGLE_USER);
        given(userRepository.findByEmail(GOOGLE_USER.email()))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(alreadySaved));
        given(userRepository.save(any(User.class))).willThrow(new DataIntegrityViolationException("duplicate email"));

        // when
        OAuthLoginResponse response = oAuthService.login(OAuthProvider.GOOGLE, CODE);

        // then
        assertThat(response.newUser()).isFalse();
        assertThat(response.email()).isEqualTo(GOOGLE_USER.email());
        then(userRepository).should(times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("유니크 위반 후 재조회가 비면 닉네임 충돌로 보고 새 접미사로 한 번 더 가입한다")
    void retriesWithNewSuffix_whenReloadIsEmpty() {
        // given — 이름 조회 뒤 저장 전에 다른 가입이 같은 닉네임을 선점했다
        given(oAuthClient.authenticate(CODE)).willReturn(GOOGLE_USER);
        given(userRepository.findByEmail(GOOGLE_USER.email())).willReturn(Optional.empty());
        given(userRepository.existsByName(anyString())).willReturn(false);
        given(userRepository.save(any(User.class)))
                .willThrow(new DataIntegrityViolationException("duplicate name"))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        OAuthLoginResponse response = oAuthService.login(OAuthProvider.GOOGLE, CODE);

        // then — 첫 시도는 원래 이름, 재시도는 조회 결과와 상관없이 접미사를 붙인 이름
        List<User> attempts = captureSavedUsers(2);
        assertThat(attempts.get(0).getName()).isEqualTo("홍길동");
        assertThat(attempts.get(1).getName()).startsWith("홍길동").isNotEqualTo("홍길동");
        assertValidNickname(attempts.get(1).getName());
        assertThat(response.newUser()).isTrue();
    }

    @Test
    @DisplayName("재조회가 비고 새 접미사 재시도도 유니크 위반이면 NoSuchElementException(500) 대신 OAUTH_NICKNAME_CONFLICT 를 던진다")
    void throwsConflict_whenRetryAlsoViolatesUniqueConstraint() {
        // given
        given(oAuthClient.authenticate(CODE)).willReturn(GOOGLE_USER);
        given(userRepository.findByEmail(GOOGLE_USER.email())).willReturn(Optional.empty());
        given(userRepository.existsByName(anyString())).willReturn(false);
        given(userRepository.save(any(User.class))).willThrow(new DataIntegrityViolationException("duplicate name"));

        // when / then
        assertThatThrownBy(() -> oAuthService.login(OAuthProvider.GOOGLE, CODE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OAUTH_NICKNAME_CONFLICT);
        then(userRepository).should(times(2)).save(any(User.class));   // 재시도는 한 번뿐이다
    }

    @Test
    @DisplayName("동시 가입 경쟁에서 일반 가입 회원이 이메일을 선점했으면 그 계정으로 로그인시키지 않는다")
    void throwsAlreadyRegistered_whenReloadFindsPasswordAccount() {
        // given
        User passwordUser = mock(User.class);
        given(passwordUser.getProvider()).willReturn(null);
        given(oAuthClient.authenticate(CODE)).willReturn(GOOGLE_USER);
        given(userRepository.findByEmail(GOOGLE_USER.email()))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(passwordUser));
        given(userRepository.save(any(User.class))).willThrow(new DataIntegrityViolationException("duplicate email"));

        // when / then
        assertThatThrownBy(() -> oAuthService.login(OAuthProvider.GOOGLE, CODE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ALREADY_REGISTERED_EMAIL);
    }

    @Test
    @DisplayName("OAuth 제공자 통신 예외는 그대로 전파되고 회원 조회·저장으로 넘어가지 않는다")
    void propagatesProviderException_withoutTouchingRepository() {
        // given — 클라이언트가 통신 실패를 비즈니스 예외로 전환해 던진다
        given(oAuthClient.authenticate(CODE)).willThrow(new BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE));

        // when / then
        assertThatThrownBy(() -> oAuthService.login(OAuthProvider.GOOGLE, CODE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE);
        then(userRepository).should(never()).findByEmail(any());
        then(userRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("인가 코드 거절 예외(401)도 그대로 전파된다")
    void propagatesAuthenticationFailure() {
        // given
        given(oAuthClient.authenticate(CODE)).willThrow(new BusinessException(ErrorCode.OAUTH_AUTHENTICATION_FAILED));

        // when / then
        assertThatThrownBy(() -> oAuthService.login(OAuthProvider.GOOGLE, CODE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.OAUTH_AUTHENTICATION_FAILED);
        then(transactionTemplate).should(never()).execute(any());
    }

    // ===== Helper =====

    private List<User> captureSavedUsers(int expectedCalls) {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        then(userRepository).should(times(expectedCalls)).save(captor.capture());
        return captor.getAllValues();
    }

    private void assertValidNickname(String nickname) {
        assertThat(nickname)
                .matches(ValidationPatterns.NAME_PATTERN)
                .hasSizeBetween(ValidationPatterns.NAME_MIN_LENGTH, ValidationPatterns.NAME_MAX_LENGTH);
    }
}
