package ccommit.stylehub.common.idempotency;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.common.util.HashUtils;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author WonJin Bae
 * @created 2026/09/18
 * @modified 2026/09/29 by WonJin - refactor: PG 멱등 키 파생을 결제 상태 기반 키로 옮기며 derivedKey 제거
 *
 * <p>
 * 한 사용자가 한 작업에 보낸 Idempotency-Key 와 요청 내용의 해시를 묶는다.
 * 같은 키에 다른 내용이 오면 해시로 구분해 거절한다.
 * </p>
 */
public record IdempotencyRequest(Long userId, IdempotentOperation operation, String key, String requestHash) {

    public static final String HEADER = "Idempotency-Key";

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    // 헤더를 보내지 않은 요청은 null 을 돌려 멱등 처리 없이 실행한다.
    public static IdempotencyRequest of(Long userId, IdempotentOperation operation, String key, Object... requestParts) {
        if (key == null) {
            return null;
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
        String canonical = Arrays.stream(requestParts).map(String::valueOf).collect(Collectors.joining("|"));
        return new IdempotencyRequest(userId, operation, key, HashUtils.sha256Hex(canonical));
    }
}
