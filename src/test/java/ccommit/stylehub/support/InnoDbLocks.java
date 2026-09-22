package ccommit.stylehub.support;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * performance_schema.data_locks 로 지금 걸려 있는 InnoDB 행 락을 조회하는 테스트 도구다.
 * 락 동작 테스트가 스레드 타이밍 추측 대신 DB 가 실제로 보고하는 락 상태를 보고 판단하게 한다.
 * </p>
 */
public class InnoDbLocks {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private final JdbcTemplate jdbcTemplate;

    public InnoDbLocks(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    // 해당 테이블 PK 행에 부여된(GRANTED) 락 모드 목록. 예: S,REC_NOT_GAP
    public List<String> grantedRowLockModes(String table, Long primaryKey) {
        return jdbcTemplate.queryForList(
                "SELECT LOCK_MODE FROM performance_schema.data_locks " +
                "WHERE OBJECT_SCHEMA = DATABASE() AND OBJECT_NAME = ? AND LOCK_TYPE = 'RECORD' " +
                "AND LOCK_STATUS = 'GRANTED' AND LOCK_DATA = ?",
                String.class, table, String.valueOf(primaryKey));
    }

    // 해당 테이블에서 락을 기다리는 요청이 보일 때까지 기다린다. 제한 시간 안에 안 보이면 false
    public boolean awaitWaitingLock(String table, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_locks " +
                    "WHERE OBJECT_SCHEMA = DATABASE() AND OBJECT_NAME = ? AND LOCK_STATUS = 'WAITING'",
                    Integer.class, table);
            if (waiting != null && waiting > 0) {
                return true;
            }
            sleep(POLL_INTERVAL);
        }
        return false;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
