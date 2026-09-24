package ccommit.stylehub.common.idempotency;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 *
 * <p>
 * 같은 Idempotency-Key 로 다시 온 요청을 한 번만 처리하고, 이후 요청에는 처음 만든 리소스를 돌려준다.
 * 키 기록과 업무 처리를 한 트랜잭션에 묶어, 업무가 실패하면 키도 남지 않아 같은 키로 다시 시도할 수 있다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    private final IdempotencyRecordRepository recordRepository;
    private final TransactionTemplate transactionTemplate;

    // 키를 업무보다 먼저 INSERT 한다. 같은 키의 동시 요청은 재고·결제 행 락을 잡기 전에 유니크 인덱스에서 기다린다.
    public <T> T execute(IdempotencyRequest request, Supplier<T> action,
                         Function<T, Long> resourceIdOf, Function<Long, T> replay) {
        if (request == null) {
            return action.get();
        }
        try {
            return transactionTemplate.execute(status -> {
                IdempotencyRecord record = recordRepository.save(IdempotencyRecord.claim(request));
                T result = action.get();
                record.complete(resourceIdOf.apply(result));
                return result;
            });
        } catch (DataIntegrityViolationException violation) {
            return replayOrReject(request, violation, replay);
        }
    }

    // 기록이 보이면 처리가 끝난 키다. 키 충돌인데 기록이 안 보이면 앞선 요청이 아직 커밋 전이다.
    private <T> T replayOrReject(IdempotencyRequest request, DataIntegrityViolationException violation,
                                 Function<Long, T> replay) {
        IdempotencyRecord existing = recordRepository.findByUserIdAndOperationAndIdempotencyKey(
                request.userId(), request.operation(), request.key()).orElse(null);

        if (existing == null) {
            if (isKeyConflict(violation)) {
                throw new BusinessException(ErrorCode.IDEMPOTENT_REQUEST_IN_PROGRESS);
            }
            throw violation;
        }
        if (!existing.isSameRequest(request.requestHash())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return replay.apply(existing.getResourceId());
    }

    private boolean isKeyConflict(DataIntegrityViolationException violation) {
        String message = violation.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(IdempotencyRecord.UNIQUE_KEY_NAME);
    }
}
