package ccommit.stylehub.common.constants;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 요청 추적 ID(traceId)를 주고받는 헤더 이름과 로그 MDC 키를 관리한다.
 * 필터·에러 응답·로그 패턴(logback-spring.xml 의 %X{traceId})이 같은 이름을 써야 한 요청의 기록이 이어지므로 한곳에 둔다.
 * </p>
 */
public final class TraceConstants {

    private TraceConstants() {}

    // 게이트웨이·프런트가 붙여 보낸 요청 ID 를 받고, 서버가 확정한 값을 응답에도 같은 이름으로 돌려준다.
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    // logback-spring.xml 의 %X{traceId} 와 이름이 같아야 로그 줄에 찍힌다.
    public static final String TRACE_ID_MDC_KEY = "traceId";
}
