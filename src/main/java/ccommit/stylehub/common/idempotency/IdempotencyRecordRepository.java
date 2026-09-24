package ccommit.stylehub.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * Idempotency-Key 처리 기록을 조회·저장한다.
 * </p>
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByUserIdAndOperationAndIdempotencyKey(
            Long userId, IdempotentOperation operation, String idempotencyKey);
}
