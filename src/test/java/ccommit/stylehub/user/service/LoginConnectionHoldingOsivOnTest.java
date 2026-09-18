package ccommit.stylehub.user.service;

import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.TestPropertySource;

/**
 * @author WonJin Bae
 * @created 2026/09/15
 * @modified 2026/09/17 by WonJin - test: 운영 프로파일이 OSIV 를 끈 뒤에도 "켜져 있을 때의 동작"을 비교할 수 있도록 open-in-view=true 를 명시
 *
 * <p>
 * OSIV를 켠 상태(Spring Boot 기본값)에서 BCrypt 실행 중 커넥션 점유를 측정하는, 운영(OSIV 꺼짐)과의 비교 기준이다.
 * 기본값이 바뀌거나 환경변수(SPRING_JPA_OPEN_IN_VIEW)로 꺼도 비교 대상이 흔들리지 않게 값을 명시한다.
 * </p>
 */
@DisplayName("BCrypt 실행 중 커넥션 점유 — OSIV 켜짐")
@TestPropertySource(properties = "spring.jpa.open-in-view=true")
class LoginConnectionHoldingOsivOnTest extends LoginConnectionHoldingTestSupport {

    @Override
    protected boolean osivEnabled() {
        return true;
    }
}
