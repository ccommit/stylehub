package ccommit.stylehub.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 테스트 설정(src/test/resources/application.properties)이 실제로 적용되는지 검증한다.
 * 설정 파일에 값을 적는 것만으로는 개발자 로컬의 application.properties 에 가려지거나 이름이 틀려 무시돼도 알 수 없으므로,
 * 클래스패스에서 먼저 읽히는 파일, 실제 DB 의 호환 모드, OSIV 인터셉터 등록 여부로 확인한다.
 * </p>
 */
@SpringBootTest
class TestApplicationPropertiesTest {

    private static final String APPLICATION_PROPERTIES = "application.properties";

    // 컨텍스트마다 다른 인메모리 DB 를 쓰도록 이름에 UUID 가 들어간다(${random.uuid}).
    private static final String UNIQUE_H2_URL_PREFIX =
            "^jdbc:h2:mem:stylehub-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("클래스패스에서 가장 먼저 읽히는 application.properties 는 테스트 리소스다 (로컬 메인 리소스 파일이 섞이지 않는다)")
    void testResourceShadowsMainResource() throws IOException {
        URL first = getClass().getClassLoader().getResource(APPLICATION_PROPERTIES);
        assertThat(first).as("테스트 리소스의 application.properties 가 클래스패스에 있어야 한다").isNotNull();

        Properties properties = new Properties();
        try (InputStream in = first.openStream()) {
            properties.load(in);
        }

        // 로컬 메인 리소스 파일(로컬 MySQL)이 앞에 오면 이 값이 H2 가 아니다
        assertThat(properties.getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:");
    }

    @Test
    @DisplayName("테스트 DB 는 컨텍스트마다 이름이 다른 H2 인메모리 DB 이고 MySQL 호환 모드로 열린다")
    void dataSourceIsUniqueH2InMySqlMode() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet mode = statement.executeQuery(
                     "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'MODE'")) {

            assertThat(connection.getMetaData().getURL()).containsPattern(UNIQUE_H2_URL_PREFIX);
            assertThat(mode.next()).isTrue();
            assertThat(mode.getString(1)).isEqualToIgnoringCase("MySQL");
        }
    }

    @Test
    @DisplayName("OSIV 는 운영과 같이 꺼져 있어 요청 범위 EntityManager 인터셉터가 등록되지 않는다")
    void openInViewIsDisabled() {
        assertThat(applicationContext.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
    }
}
