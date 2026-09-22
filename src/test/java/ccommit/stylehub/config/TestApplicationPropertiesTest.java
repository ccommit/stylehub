package ccommit.stylehub.config;

import ccommit.stylehub.support.container.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 테스트가 실제로 MySQL 8.0(InnoDB, REPEATABLE-READ)·Redis 컨테이너 위에서 도는지 검증한다.
 * 설정이 가려지거나 무시돼 다른 DB 로 폴백해도 다른 테스트는 조용히 통과할 수 있어서 따로 확인한다.
 * </p>
 */
@SpringBootTest
class TestApplicationPropertiesTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("클래스패스에서 가장 먼저 읽히는 application.properties 는 테스트 리소스다 (로컬 메인 리소스 파일이 섞이지 않는다)")
    void testResourceShadowsMainResource() throws IOException {
        URL first = getClass().getClassLoader().getResource("application.properties");
        assertThat(first).isNotNull();

        Properties properties = new Properties();
        try (InputStream in = first.openStream()) {
            properties.load(in);
        }

        // 로컬 메인 리소스 파일이 앞에 오면 실제 결제 URL 이 보인다
        assertThat(properties.getProperty("toss.payments.confirm-url")).contains(".invalid");
    }

    @Test
    @DisplayName("테스트 DB 는 MySQL 8.0 이고 저장 엔진은 InnoDB, 격리 수준은 REPEATABLE-READ 다")
    void dataSourceIsMySql80InnoDb() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet settings = statement.executeQuery(
                     "SELECT @@default_storage_engine, @@transaction_isolation")) {

            DatabaseMetaData metaData = connection.getMetaData();
            assertThat(metaData.getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(metaData.getDatabaseProductVersion()).startsWith("8.0.");

            assertThat(settings.next()).isTrue();
            assertThat(settings.getString(1)).isEqualTo("InnoDB");
            assertThat(settings.getString(2)).isEqualTo("REPEATABLE-READ");
        }
    }

    @Test
    @DisplayName("Redis 는 환경 변수가 있어도 테스트가 띄운 컨테이너를 가리킨다")
    void redisPointsToContainer() {
        assertThat(environment.getProperty("spring.data.redis.port"))
                .isEqualTo(String.valueOf(SharedContainers.redisPort()));

        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            assertThat(connection.ping()).isEqualTo("PONG");
        }
    }

    @Test
    @DisplayName("OSIV 는 운영과 같이 꺼져 있어 요청 범위 EntityManager 인터셉터가 등록되지 않는다")
    void openInViewIsDisabled() {
        assertThat(applicationContext.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
    }
}
