package ccommit.stylehub.common.config;

import ccommit.stylehub.common.constants.TraceConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * 요청마다 traceId 를 정해 로그 MDC 와 응답 헤더(X-Request-Id)에 싣는 필터이다.
 * 에러 응답의 traceId 도 이 값을 쓰므로, 사용자가 받은 traceId 로 같은 요청의 서버 로그를 바로 찾을 수 있다.
 * </p>
 *
 * <p>
 * 가장 앞 순서(HIGHEST_PRECEDENCE)로 둔다. Spring Session 필터(기본 순서 Integer.MIN_VALUE + 50)처럼 뒤따르는 필터가
 * 처리 중 남기는 로그와, 그 필터들이 직접 끝낸 응답에도 같은 traceId 가 붙어야 하기 때문이다.
 * 단, 예외가 이 필터 밖(서블릿 컨테이너)까지 전파돼 컨테이너가 남기는 로그는 MDC 를 비운 뒤라 traceId 가 없다.
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /*
     * 클라이언트가 보낸 X-Request-Id 는 영문·숫자·하이픈·밑줄 8~64자일 때만 받아들인다.
     * 줄바꿈·공백이 섞인 값을 그대로 MDC 에 넣으면 모든 로그 줄에 붙어 가짜 로그 줄을 끼워 넣을 수 있고(로그 인젝션),
     * 길이 제한이 없으면 요청 하나가 그 요청의 모든 로그 줄을 키운다. UUID(36자)·32자리 hex 같은 흔한 형식은 통과한다.
     * 형식이 맞지 않으면 거절하지 않고 서버가 새로 만든다. 추적 ID 때문에 정상 요청이 실패하면 안 되기 때문이다.
     */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TraceConstants.REQUEST_ID_HEADER));
        MDC.put(TraceConstants.TRACE_ID_MDC_KEY, traceId);
        // 체인보다 먼저 넣는다. 뒤에서 응답 본문이 먼저 커밋되면 그 뒤에 추가한 헤더는 전송되지 않는다.
        response.setHeader(TraceConstants.REQUEST_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 톰캣 요청 스레드는 풀로 돌아가 다음 요청을 처리한다. 비우지 않으면 다음 요청(특히 이 필터를 거치지 않는 경로)의
            // 로그에 이전 요청의 값이 찍힌다. 이 필터가 요청의 가장 바깥 경계이므로 요청 중 쌓인 MDC 를 모두 비운다.
            MDC.clear();
        }
    }

    private static String resolveTraceId(String requestedId) {
        if (requestedId != null && SAFE_REQUEST_ID.matcher(requestedId).matches()) {
            return requestedId;
        }
        return UUID.randomUUID().toString();
    }
}
