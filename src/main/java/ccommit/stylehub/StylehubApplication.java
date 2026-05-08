package ccommit.stylehub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 15:35 by WonJin - feat: 전체 도메인 JPA 엔티티 생성
 * @modified 2026/03/16 18:16 by WonJin - feat: 회원 API 개발 (회원가입, 로그인, 구글 OAuth, 포인트 지급)
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/05/06 by WonJin - feat: @EnableRedisHttpSession 적용 — 다중 인스턴스 환경 세션 외부화
 *
 * <p>
 * Spring Boot 애플리케이션의 메인 진입점이다.
 * JPA Auditing, 스케줄링, 설정 클래스 자동 스캔, Redis 세션 저장소를 활성화한다.
 * </p>
 */

@EnableJpaAuditing
@EnableScheduling
@ConfigurationPropertiesScan
@EnableRedisHttpSession
@SpringBootApplication
public class StylehubApplication {

    public static void main(String[] args) {
        SpringApplication.run(StylehubApplication.class, args);
    }

}
