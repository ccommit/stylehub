package ccommit.stylehub.common.config;

import ccommit.stylehub.user.enums.OAuthProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/03/23 by WonJin - feat: 인증/역할 검증 인터셉터 등록
 * @modified 2026/03/27 by WonJin - feat: 상품 조회 공개 API 경로 인증 제외 추가
 *
 * <p>
 * Spring MVC 커스텀 Converter와 인터셉터를 등록한다.
 * provider 문자열을 OAuthProvider enum으로 자동 변환한다.
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RoleCheckInterceptor roleCheckInterceptor;

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new StringToOAuthProviderConverter());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1순위: 인증 검사 — 로그인 여부 확인 (비로그인 API는 제외)
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**") // 모든 API에 적용
                .excludePathPatterns(
                        "/api/v1/users/sign-up/**", // 회원가입 — 비로그인 상태에서 호출
                        "/api/v1/users/login",  // 로그인 — 비로그인 상태에서 호출
                        "/api/v1/users/oauth/**",  // OAuth — 비로그인 상태에서 호출
                        "/api/v1/products/**",     // 상품 조회 — 비인증 공개 API
                        "/api/v1/payments/**",     // 토스 결제 콜백 — 토스 서버에서 리다이렉트
                        "/v3/api-docs/**",         // Swagger API 문서
                        "/swagger-ui/**",          // Swagger UI 웹 화면
                        "/actuator/**"             // Spring Boot Actuator 모니터링
                );

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
