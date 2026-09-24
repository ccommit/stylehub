package ccommit.stylehub.common.exception;

import ccommit.stylehub.common.constants.TraceConstants;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * @author WonJin Bae
 * @created 2026/03/22
 * @modified 2026/03/24 by WonJin - refactor: bwj 패키지명 ccommit으로 변경, timestamp/path/traceId 필드 추가
 * @modified 2026/09/17 by WonJin - refactor: traceId 를 매번 새 UUID 대신 요청 진입 필터가 MDC 에 넣은 값으로 채움 — 응답의 traceId 로 서버 로그를 찾을 수 있게
 *
 * <p>
 * 클라이언트에 반환되는 에러 응답 포맷을 정의한다.
 * timestamp, path, traceId로 장애 추적 및 모니터링을 지원한다.
 * traceId 는 TraceIdFilter 가 정한 요청 단위 값이며, 응답 헤더 X-Request-Id 와 로그 줄에 찍히는 값과 같다.
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
        return of(errorCode, errorCode.getMessage(), path);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, String path) {
        return new ErrorResponse(
                errorCode.getStatus().value(),
                errorCode.getCode(),
                message,
                LocalDateTime.now(),
                path,
                currentTraceId()
        );
    }

    // 필터를 거치지 않은 호출(필터 없이 구성한 MockMvc 등)에서만 새로 만든다. 이때 값은 로그와 연결되지 않는다.
    private static String currentTraceId() {
        String traceId = MDC.get(TraceConstants.TRACE_ID_MDC_KEY);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}
