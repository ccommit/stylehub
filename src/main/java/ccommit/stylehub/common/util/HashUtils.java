package ccommit.stylehub.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 요청 내용 비교와 외부 API 멱등 키 생성에 쓰는 SHA-256 해시를 만든다.
 * </p>
 */
public final class HashUtils {

    private HashUtils() {
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 지원하지 않는 JVM 입니다", e);
        }
    }
}
