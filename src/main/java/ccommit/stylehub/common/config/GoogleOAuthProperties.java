package ccommit.stylehub.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 구글 OAuth 인증에 필요한 설정값을 application.yml에서 바인딩한다.
 * record로 불변 객체를 유지한다.
 * </p>
 */

@ConfigurationProperties(prefix = "google")
public record GoogleOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri
) {
}
