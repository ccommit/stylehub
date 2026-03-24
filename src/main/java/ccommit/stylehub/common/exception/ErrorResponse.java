package ccommit.stylehub.common.exception;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * @author WonJin Bae
 * @created 2026/03/22
 * @modified 2026/03/24 by WonJin - refactor: bwj 패키지명 ccommit으로 변경, timestamp/path/traceId 필드 추가
 *
 * <p>
 * 클라이언트에 반환되는 에러 응답 포맷을 정의한다.
 * timestamp, path, traceId로 장애 추적 및 모니터링을 지원한다.
 * </p>
 */
public record ErrorResponse(
        int status,
        String code,
        String message,
        LocalDateTime timestamp,
        String path,
        String traceId
) {
    public static ErrorResponse of(ErrorCode errorCode, String path) {
        return new ErrorResponse(
                errorCode.getStatus().value(),
                errorCode.getCode(),
                errorCode.getMessage(),
                LocalDateTime.now(),
                path,
                UUID.randomUUID().toString()
        );
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, String path) {
        return new ErrorResponse(
                errorCode.getStatus().value(),
                errorCode.getCode(),
                message,
                LocalDateTime.now(),
                path,
                UUID.randomUUID().toString()
        );
    }
}
