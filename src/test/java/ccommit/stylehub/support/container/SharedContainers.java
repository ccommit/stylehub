package ccommit.stylehub.support.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 테스트 JVM 하나가 함께 쓰는 MySQL·Redis 컨테이너다.
 * 처음 필요할 때 한 번만 띄우고, JVM 이 끝나면 Testcontainers 가 정리한다.
 * </p>
 */
public final class SharedContainers {

    // docker-compose.yml 의 MySQL·Redis 와 같은 버전
    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.0");
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");
    private static final int REDIS_PORT = 6379;

    private static MySQLContainer mysql;
    private static GenericContainer<?> redis;

    private SharedContainers() {
    }

    public static synchronized MySQLContainer mysql() {
        if (mysql == null) {
            // 락 조회(performance_schema)와 스키마 생성에 권한 제한이 없도록 root 로 접속한다
            mysql = new MySQLContainer(MYSQL_IMAGE)
                    .withDatabaseName("stylehub")
                    .withUsername("root")
                    .withPassword("test")
                    // 운영 접속 URL 과 같은 시간대·인코딩 옵션
                    .withUrlParam("serverTimezone", "Asia/Seoul")
                    .withUrlParam("characterEncoding", "UTF-8");
            mysql.start();
        }
        return mysql;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT);
            redis.start();
        }
        return redis;
    }

    public static int redisPort() {
        return redis().getMappedPort(REDIS_PORT);
    }

    // 공유 MySQL 에 별도 데이터베이스를 만들고 그 JDBC URL 을 돌려준다. JPA 스키마와 테이블 이름이 겹치는 테스트용
    public static String mysqlDatabaseUrl(String database) {
        MySQLContainer container = mysql();
        try (Connection connection = container.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + database);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return container.getJdbcUrl().replaceFirst("/" + container.getDatabaseName() + "(?=\\?|$)", "/" + database);
    }
}
