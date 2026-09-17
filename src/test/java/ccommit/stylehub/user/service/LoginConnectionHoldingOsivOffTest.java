package ccommit.stylehub.user.service;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.TestPropertySource;

/**
 * @author WonJin Bae
 * @created 2026/09/15
 * @modified 2026/09/17 by WonJin - test: 운영 프로파일의 OSIV 설정과 같은 조건임을 주석에 명시
 *
 * <p>
 * OSIV를 끈 상태에서 같은 시나리오를 실행해, 커넥션 점유가 OSIV 때문인지 비교한다.
 * 운영 프로파일(application-prod.properties)의 spring.jpa.open-in-view=false 와 같은 조건이다.
 * </p>
 */
@DisplayName("BCrypt 실행 중 커넥션 점유 — OSIV 꺼짐")
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class LoginConnectionHoldingOsivOffTest extends LoginConnectionHoldingTestSupport {

    @Override
    protected boolean osivEnabled() {
        return false;
    }
}
