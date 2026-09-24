package ccommit.stylehub.common.idempotency;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author WonJin Bae
 * @created 2026/09/18
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
        return new IdempotencyRequest(userId, operation, key, sha256(canonical));
    }

    // 같은 사용자·작업·키·요청 내용이면 항상 같은 값이다. PG 처럼 멱등 키를 받는 외부 API 에 넘겨 재시도가 중복 처리되지 않게 한다.
    public String derivedKey() {
        return sha256(userId + "|" + operation + "|" + key + "|" + requestHash);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 지원하지 않는 JVM 입니다", e);
        }
    }
}
