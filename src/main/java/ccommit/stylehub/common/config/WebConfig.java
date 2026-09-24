package ccommit.stylehub.common.config;

import ccommit.stylehub.user.enums.OAuthProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/03/23 by WonJin - feat: 인증/역할 검증 인터셉터 등록
 * @modified 2026/03/27 by WonJin - feat: 상품 조회 공개 API 경로 인증 제외 추가
 * @modified 2026/04/16 by WonJin - refactor: @RestController 공통 프리픽스 /api/v1 자동 부여
 * @modified 2026/09/17 by WonJin - fix: 결제 인증 제외 범위를 토스 콜백(success/fail)으로 축소 — 결제 취소 API 무인증 호출 차단
 * @modified 2026/09/24 by WonJin - feat: 활성 쿠폰 이벤트 목록(GET /coupon-events)을 비로그인 공개 API 로 전환
 * @modified 2026/09/24 by WonJin - feat: 공개 API 목록을 상수로 분리해 API 문서와 공유, /api/v1 프리픽스를 애플리케이션 컨트롤러로 한정(springdoc 문서 경로 보존)
 *
 * <p>
 * Spring MVC 커스텀 Converter, 인터셉터, 공통 경로 프리픽스를 등록한다.
 * provider 문자열을 OAuthProvider enum으로 자동 변환한다.
 * 모든 @RestController에는 /api/v1 프리픽스가 자동 부여되어, 각 컨트롤러의 @RequestMapping에서 중복 작성이 불필요하다.
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    public static final String API_PREFIX = "/api/v1";

    // 비로그인으로 호출할 수 있는 API. 인증 인터셉터 제외와 API 문서의 세션 요구 표시가 같은 목록을 쓴다
    public static final List<String> PUBLIC_API_PATTERNS = List.of(
            API_PREFIX + "/users/sign-up/**",   // 회원가입
            API_PREFIX + "/users/login",        // 로그인
            API_PREFIX + "/users/oauth/**",     // OAuth
            API_PREFIX + "/products/**",        // 상품 조회
            API_PREFIX + "/coupon-events",      // 활성 쿠폰 이벤트 목록 — 이 경로에는 GET 만 있다
            API_PREFIX + "/payments/success",   // 토스 결제 콜백 — 결제 취소 API는 로그인 필요
            API_PREFIX + "/payments/fail"       // 토스 결제 콜백
    );

    private static final String APPLICATION_PACKAGE = "ccommit.stylehub";

    private final AuthInterceptor authInterceptor;
    private final RoleCheckInterceptor roleCheckInterceptor;

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToOAuthProviderConverter());
    }

    // 애플리케이션 @RestController 요청 매핑 앞에 /api/v1을 붙인다. springdoc 의 문서 컨트롤러도 @RestController 라 패키지로 한정한다
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_PREFIX,
                HandlerTypePredicate.forAnnotation(RestController.class)
                        .and(HandlerTypePredicate.forBasePackage(APPLICATION_PACKAGE)));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1순위: 인증 검사 — 로그인 여부 확인 (비로그인 API는 제외)
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(API_PREFIX + "/**") // 모든 API에 적용
                .excludePathPatterns(PUBLIC_API_PATTERNS);

        // 2순위: 역할 검사 — @RequiredRole이 붙은 메서드만 역할 검증
        registry.addInterceptor(roleCheckInterceptor)
                .addPathPatterns("/api/**");
    }

    private static class StringToOAuthProviderConverter implements Converter<String, OAuthProvider> {

        @Override
        public OAuthProvider convert(String source) {
            return OAuthProvider.valueOf(source.toUpperCase());
        }
    }
}
