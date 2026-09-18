package ccommit.stylehub.user.controller;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.dto.response.OAuthUserInfo;
import ccommit.stylehub.user.enums.UserRole;
import ccommit.stylehub.user.repository.UserRepository;
import ccommit.stylehub.user.service.GoogleOAuthClient;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * OAuth 인가 요청과 콜백 사이의 state 검증을 컨트롤러·인터셉터·예외 처리까지 포함해 검증하는 통합 테스트이다.
 * OAuthService가 생성 시점에 provider()로 클라이언트를 등록해 기본 목이면 깨지므로, 구글 클라이언트 목은 CALLS_REAL_METHODS로 둔다.
 * </p>
 */
@SpringBootTest
class OAuthCallbackStateTest {

    private static final String AUTHORIZATION_PATH = "/api/v1/users/oauth/google";
    private static final String CALLBACK_PATH = "/api/v1/users/oauth/google/callback";

    @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
    private GoogleOAuthClient googleOAuthClient;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;

    private final List<String> createdEmails = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        willAnswer(invocation -> "https://accounts.test/auth?state=" + invocation.getArgument(0))
                .given(googleOAuthClient).getAuthorizationUrl(anyString());
    }

    @AfterEach
    void cleanUp() {
        createdEmails.forEach(email -> userRepository.findByEmail(email).ifPresent(userRepository::delete));
        createdEmails.clear();
    }

    @Test
    @DisplayName("인가 URL 요청 시 추측 불가능한 state 를 세션에 저장하고 같은 값을 URL 에 싣는다")
    void issuesStateIntoSessionAndUrl() throws Exception {
        MvcResult result = mockMvc.perform(get(AUTHORIZATION_PATH))
                .andExpect(status().isOk())
                .andReturn();

        String state = (String) result.getRequest().getSession(false).getAttribute(SessionConstants.SESSION_OAUTH_STATE);
        // 32바이트를 패딩 없는 base64url 로 인코딩하면 43자다
        assertThat(state).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(result.getResponse().getContentAsString()).contains("state=" + state);
        then(googleOAuthClient).should().getAuthorizationUrl(state);
    }

    @Test
    @DisplayName("콜백에 state 가 없으면 400 이고 구글 호출 없이 로그인 세션 속성도 생기지 않는다")
    void rejectsCallback_whenStateMissing() throws Exception {
        MockHttpSession session = startAuthorization();

        MvcResult result = mockMvc.perform(get(CALLBACK_PATH).param("code", "auth-code").session(session))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInvalidState(result);
        assertNoLoginSession(result);
        then(googleOAuthClient).should(never()).authenticate(anyString());
    }

    @Test
    @DisplayName("콜백의 state 가 세션 값과 다르면 400 이고, 세션의 state 는 제거되어 원래 값으로도 재시도할 수 없다")
    void rejectsCallback_whenStateMismatch() throws Exception {
        MockHttpSession session = startAuthorization();
        String issuedState = (String) session.getAttribute(SessionConstants.SESSION_OAUTH_STATE);

        MvcResult mismatch = mockMvc.perform(get(CALLBACK_PATH)
                        .param("code", "attacker-code")
                        .param("state", "forged-state")
                        .session(session))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInvalidState(mismatch);
        assertNoLoginSession(mismatch);
        assertThat(session.getAttribute(SessionConstants.SESSION_OAUTH_STATE)).isNull();

        // 1회용: 실패한 뒤에는 발급받은 원래 값도 통과하지 못한다
        MvcResult replay = mockMvc.perform(get(CALLBACK_PATH)
                        .param("code", "auth-code")
                        .param("state", issuedState)
                        .session(session))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInvalidState(replay);
        assertNoLoginSession(replay);
        then(googleOAuthClient).should(never()).authenticate(anyString());
    }

    @Test
    @DisplayName("인가 요청을 시작한 세션 없이 콜백만 오면 400 이다")
    void rejectsCallback_whenNoSession() throws Exception {
        MvcResult result = mockMvc.perform(get(CALLBACK_PATH)
                        .param("code", "auth-code")
                        .param("state", "any-state"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertInvalidState(result);
        assertNoLoginSession(result);
        then(googleOAuthClient).should(never()).authenticate(anyString());
    }

    @Test
    @DisplayName("발급한 state 로 돌아오면 로그인 세션이 새로 만들어지고 state 는 남지 않는다")
    void logsIn_whenStateMatches() throws Exception {
        // given
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String email = "oauth-state-" + unique + "@test.com";
        createdEmails.add(email);
        willReturn(new OAuthUserInfo("state" + unique, email, "sub-" + unique))
                .given(googleOAuthClient).authenticate("auth-code");

        MockHttpSession session = startAuthorization();
        String issuedState = (String) session.getAttribute(SessionConstants.SESSION_OAUTH_STATE);

        // when
        MvcResult result = mockMvc.perform(get(CALLBACK_PATH)
                        .param("code", "auth-code")
                        .param("state", issuedState)
                        .session(session))
                .andExpect(status().isOk())
                .andReturn();

        // then — 세션 고정 방지로 기존 세션은 무효화되고 새 세션에 로그인 정보가 들어간다
        HttpSession loginSession = result.getRequest().getSession(false);
        assertThat(session.isInvalid()).isTrue();
        assertThat(loginSession).isNotNull().isNotSameAs(session);
        assertThat(loginSession.getAttribute(SessionConstants.SESSION_USER_ID))
                .isEqualTo(userRepository.findByEmail(email).orElseThrow().getUserId());
        assertThat(loginSession.getAttribute(SessionConstants.SESSION_USER_ROLE)).isEqualTo(UserRole.USER);
        assertThat(loginSession.getAttribute(SessionConstants.SESSION_OAUTH_STATE)).isNull();
    }

    // ===== Helper =====

    // 실제 흐름처럼 인가 URL 요청으로 state가 담긴 세션을 만든다
    private MockHttpSession startAuthorization() throws Exception {
        MvcResult result = mockMvc.perform(get(AUTHORIZATION_PATH))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void assertInvalidState(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentAsString()).contains(ErrorCode.INVALID_OAUTH_STATE.getCode());
    }

    private void assertNoLoginSession(MvcResult result) {
        HttpSession session = result.getRequest().getSession(false);
        if (session == null) {
            return;
        }
        assertThat(session.getAttribute(SessionConstants.SESSION_USER_ID)).isNull();
        assertThat(session.getAttribute(SessionConstants.SESSION_USER_ROLE)).isNull();
    }
}
