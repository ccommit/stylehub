package ccommit.stylehub.common.config;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 등록된 모든 API 를 순회하며 인증·역할 요구사항을 한 곳에 고정하는 회귀 테스트이다.
 * 공개 API 와 역할 제한 없는 로그인 API 를 명시해, 새 API 를 추가하면 접근 정책을 의식적으로 정하게 만든다.
 * </p>
 */
@SpringBootTest
class ApiAuthorizationMatrixTest {

    private static final String APPLICATION_PACKAGE = "ccommit.stylehub";

    // 비로그인으로 호출할 수 있는 API. WebConfig 의 인증 제외 경로와 일치해야 한다.
    private static final Set<String> PUBLIC_APIS = Set.of(
            "POST /api/v1/users/sign-up",
            "POST /api/v1/users/sign-up/store",
            "POST /api/v1/users/login",
            "GET /api/v1/users/oauth/{provider}",
            "GET /api/v1/users/oauth/{provider}/callback",
            "GET /api/v1/products",
            "GET /api/v1/products/{productId}",
            "GET /api/v1/coupon-events",
            // 토스 결제창이 리다이렉트하는 콜백이라 세션을 기대할 수 없다
            "GET /api/v1/payments/success",
            "GET /api/v1/payments/fail"
    );

    // 로그인만 요구하고 @RequiredRole 을 두지 않는 API. 역할 제한을 빠뜨린 API 가 조용히 모든 역할에 열리지 않게 명시한다.
    private static final Set<String> LOGIN_ONLY_APIS = Set.of(
            "POST /api/v1/users/logout"
    );

    // 경로 변수는 인터셉터가 거절하기 전 매핑만 되면 되므로 숫자 1 을 넣는다. provider 는 enum 이라 실제 값을 쓴다.
    private static final Map<String, String> PATH_VARIABLE_SAMPLES = Map.of("provider", "google");
    private static final String DEFAULT_PATH_VARIABLE_SAMPLE = "1";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");

    private static final Long SESSION_USER_ID = 1L;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    @DisplayName("모든 API 는 HTTP 메서드를 명시한다 (메서드가 없는 매핑은 모든 메서드에 열려 접근 정책을 목록으로 표현할 수 없다)")
    void everyApiDeclaresHttpMethod() {
        List<String> withoutMethod = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            if (isApplicationHandler(handlerMethod) && info.getMethodsCondition().getMethods().isEmpty()) {
                withoutMethod.add(handlerMethod.getShortLogMessage());
            }
        });

        assertThat(withoutMethod).isEmpty();
    }

    @Test
    @DisplayName("공개·로그인 전용 목록의 항목은 모두 실제로 등록된 API 다 (경로가 바뀌면 목록도 함께 고쳐야 한다)")
    void declaredApisExist() {
        List<String> registered = collectApis().stream().map(Api::key).toList();

        assertThat(registered).containsAll(PUBLIC_APIS);
        assertThat(registered).containsAll(LOGIN_ONLY_APIS);
        assertThat(PUBLIC_APIS).doesNotContainAnyElementsOf(LOGIN_ONLY_APIS);
    }

    @Test
    @DisplayName("역할 제한이 없는 로그인 API 는 LOGIN_ONLY_APIS 에 명시된 것뿐이다")
    void apisWithoutRequiredRoleAreDeclared() {
        List<String> undeclared = collectApis().stream()
                .filter(api -> !PUBLIC_APIS.contains(api.key()))
                .filter(api -> requiredRole(api.handler()) == null)
                .map(Api::key)
                .filter(key -> !LOGIN_ONLY_APIS.contains(key))
                .toList();
        List<String> loginOnlyWithRole = collectApis().stream()
                .filter(api -> LOGIN_ONLY_APIS.contains(api.key()))
                .filter(api -> requiredRole(api.handler()) != null)
                .map(Api::key)
                .toList();

        assertThat(undeclared)
                .as("@RequiredRole 을 빠뜨렸거나, 모든 로그인 사용자에게 여는 것이 의도라면 LOGIN_ONLY_APIS 에 추가해야 한다")
                .isEmpty();
        assertThat(loginOnlyWithRole).as("LOGIN_ONLY_APIS 에 적었지만 역할 제한이 있다").isEmpty();
    }

    @TestFactory
    @DisplayName("공개 API 요청에는 인증 인터셉터가 적용되지 않는다")
    Stream<DynamicTest> publicApisSkipAuthentication() {
        return collectApis().stream()
                .filter(api -> PUBLIC_APIS.contains(api.key()))
                .map(api -> DynamicTest.dynamicTest(api.key() + " → 인증 인터셉터 제외", () -> {
                    HandlerExecutionChain chain = executionChain(api, null);

                    assertThat(chain.getInterceptorList())
                            .noneMatch(AuthInterceptor.class::isInstance);
                }));
    }

    @TestFactory
    @DisplayName("공개 목록에 없는 API 는 비로그인 요청을 401(A001)로 거절한다")
    Stream<DynamicTest> nonPublicApisRejectAnonymous() {
        return collectApis().stream()
                .filter(api -> !PUBLIC_APIS.contains(api.key()))
                .map(api -> DynamicTest.dynamicTest(api.key() + " 비로그인 → 401", () ->
                        mockMvc.perform(request(HttpMethod.valueOf(api.method().name()), api.sampleUri()))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()))));
    }

    @TestFactory
    @DisplayName("@RequiredRole 이 있는 API 는 허용되지 않은 역할을 403(A002)으로 거절하고, 허용된 역할은 인터셉터를 통과시킨다")
    Stream<DynamicTest> roleRestrictedApisRejectOtherRoles() {
        return collectApis().stream()
                .filter(api -> requiredRole(api.handler()) != null)
                .flatMap(api -> Arrays.stream(UserRole.values()).map(role -> {
                    boolean allowed = allowedRoles(api.handler()).contains(role);
                    String name = api.key() + " as " + role + (allowed ? " → 통과" : " → 403");
                    return DynamicTest.dynamicTest(name, () -> {
                        if (allowed) {
                            assertInterceptorsPass(api, role);
                            return;
                        }
                        mockMvc.perform(request(HttpMethod.valueOf(api.method().name()), api.sampleUri())
                                        .session(loginSession(role)))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
                    });
                }));
    }

    // ===== Helper =====

    private record Api(RequestMethod method, String pattern, HandlerMethod handler) {

        String key() {
            return method + " " + pattern;
        }

        String sampleUri() {
            Matcher matcher = PATH_VARIABLE.matcher(pattern);
            StringBuilder uri = new StringBuilder();
            while (matcher.find()) {
                String sample = PATH_VARIABLE_SAMPLES.getOrDefault(matcher.group(1), DEFAULT_PATH_VARIABLE_SAMPLE);
                matcher.appendReplacement(uri, Matcher.quoteReplacement(sample));
            }
            matcher.appendTail(uri);
            return uri.toString();
        }
    }

    private List<Api> collectApis() {
        List<Api> apis = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            if (!isApplicationHandler(handlerMethod)) {
                return;
            }
            for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                for (String pattern : info.getPatternValues()) {
                    apis.add(new Api(method, pattern, handlerMethod));
                }
            }
        });
        apis.sort(Comparator.comparing(Api::key));
        assertThat(apis).as("컨트롤러 매핑을 찾지 못했다").isNotEmpty();
        return apis;
    }

    private boolean isApplicationHandler(HandlerMethod handlerMethod) {
        return handlerMethod.getBeanType().getPackageName().startsWith(APPLICATION_PACKAGE);
    }

    // RoleCheckInterceptor 와 같은 규칙: 메서드 레벨이 클래스 레벨보다 우선한다.
    private RequiredRole requiredRole(HandlerMethod handlerMethod) {
        RequiredRole onMethod = handlerMethod.getMethodAnnotation(RequiredRole.class);
        return onMethod != null ? onMethod : handlerMethod.getBeanType().getAnnotation(RequiredRole.class);
    }

    private Set<UserRole> allowedRoles(HandlerMethod handlerMethod) {
        return EnumSet.copyOf(Arrays.asList(requiredRole(handlerMethod).value()));
    }

    // 핸들러가 기대한 메서드인지도 확인해, 더미 경로 변수 때문에 다른 API 로 매핑된 경우를 걸러 낸다.
    private HandlerExecutionChain executionChain(Api api, MockHttpSession session) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                context.getServletContext(), api.method().name(), api.sampleUri());
        if (session != null) {
            request.setSession(session);
        }
        ServletRequestPathUtils.parseAndCache(request);

        HandlerExecutionChain chain = handlerMapping.getHandler(request);

        assertThat(chain).as("%s 에 매핑되는 핸들러가 없다", api.sampleUri()).isNotNull();
        assertThat(((HandlerMethod) chain.getHandler()).getMethod()).isEqualTo(api.handler().getMethod());
        return chain;
    }

    // 허용된 역할은 실제로 호출하면 부작용이 생길 수 있어, 체인의 인터셉터 preHandle 만 순서대로 실행해 통과 여부를 본다.
    private void assertInterceptorsPass(Api api, UserRole role) throws Exception {
        MockHttpSession session = loginSession(role);
        HandlerExecutionChain chain = executionChain(api, session);
        MockHttpServletRequest request = new MockHttpServletRequest(
                context.getServletContext(), api.method().name(), api.sampleUri());
        request.setSession(session);

        assertThat(chain.getInterceptorList())
                .as("인증·역할 인터셉터가 체인에 없으면 통과 여부를 확인하는 의미가 없다")
                .anyMatch(AuthInterceptor.class::isInstance)
                .anyMatch(RoleCheckInterceptor.class::isInstance);
        for (HandlerInterceptor interceptor : chain.getInterceptorList()) {
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), chain.getHandler()))
                    .as("%s 가 %s 역할 요청을 막았다", interceptor.getClass().getSimpleName(), role)
                    .isTrue();
        }
    }

    private MockHttpSession loginSession(UserRole role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionConstants.SESSION_USER_ID, SESSION_USER_ID);
        session.setAttribute(SessionConstants.SESSION_USER_ROLE, role);
        return session;
    }
}
