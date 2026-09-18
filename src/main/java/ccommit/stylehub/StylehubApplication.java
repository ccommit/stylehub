package ccommit.stylehub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 15:35 by WonJin - feat: 전체 도메인 JPA 엔티티 생성
 * @modified 2026/03/16 18:16 by WonJin - feat: 회원 API 개발 (회원가입, 로그인, 구글 OAuth, 포인트 지급)
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/05/06 by WonJin - feat: @EnableRedisHttpSession 적용 — 다중 인스턴스 환경 세션 외부화
 * @modified 2026/09/17 by WonJin - fix: @EnableRedisHttpSession 제거하고 Boot 세션 스타터 자동 설정 사용 — Boot 4 세션 자동 설정 모듈이 없어 쿠키·네임스페이스·타임아웃 설정이 적용되지 않던 문제 해결(어노테이션이 남아 있으면 저장소 자동 설정이 물러난다)
 * @modified 2026/09/18 by WonJin - refactor: @EnableScheduling 을 SchedulingConfig 로 옮겨 테스트에서 끌 수 있게 함
 *
 * <p>
 * Spring Boot 애플리케이션의 메인 진입점이다.
 * Redis 세션 저장소는 spring-boot-starter-session-data-redis 자동 설정이 구성한다.
 * </p>
 */

@EnableJpaAuditing
@EnableCaching
@ConfigurationPropertiesScan
@SpringBootApplication
public class StylehubApplication {

    public static void main(String[] args) {
        SpringApplication.run(StylehubApplication.class, args);
    }

}
