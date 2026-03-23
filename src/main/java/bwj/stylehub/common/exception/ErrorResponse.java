package bwj.stylehub.common.exception;

import java.time.LocalDateTime;
import java.util.UUID;

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
