package ccommit.stylehub.common.aop;

import ccommit.stylehub.common.util.StopWatch;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/01 by WonJin - feat: finally 블록에 종료 로그 추가
 * @modified 2026/09/17 by WonJin - refactor: 포인트컷을 주문·결제 @RestController 로 좁혀 요청당 INFO 1줄만 남기고, 인자는 DEBUG 로 내림, 예외 ERROR 로그 제거(GlobalExceptionHandler 로 일원화)
 *
 * <p>
 * 주문/결제 API 호출을 컨트롤러 진입점에서 요청당 한 번 기록하는 AOP이다.
 * 서비스 코드를 오염시키지 않고 어떤 API 가 얼마나 걸렸는지 추적할 수 있다.
 * </p>
 *
 * <p>
 * 예전 포인트컷은 order·payment 패키지 전체라 요청 하나에 컨트롤러·서비스·검증기·리포지토리·PG 클라이언트 호출마다
 * INFO 가 여러 줄씩 남았고, 같은 예외가 계층마다 ERROR 로 반복 기록됐다. 이제는 컨트롤러 메서드만 감싼다.
 * 한 요청의 하위 호출 로그는 traceId(MDC)로 묶어 볼 수 있으므로 계층마다 따로 남길 필요가 없다.
 * </p>
 *
 * <ul>
 *     <li>INFO: HTTP 메서드·경로·핸들러·처리 시간·결과(정상이면 OK, 예외면 예외 타입) — 요청당 1줄</li>
 *     <li>DEBUG: 인자 값. 배송지·결제 키 같은 값이 들어 있어 운영 기본 레벨(INFO)에서는 남기지 않는다</li>
 *     <li>예외 자체(메시지·스택 트레이스)는 GlobalExceptionHandler 가 한 번만 기록한다</li>
 * </ul>
 */
@Aspect
@Component
public class ApiLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(ApiLoggingAspect.class);

    private static final String OUTCOME_OK = "OK";
    private static final String UNKNOWN = "-";

    @Around("@within(org.springframework.web.bind.annotation.RestController)" +
            " && (within(ccommit.stylehub.order..*) || within(ccommit.stylehub.payment..*))")
    public Object logOrderAndPaymentApi(ProceedingJoinPoint pjp) throws Throwable {
        String handler = pjp.getSignature().toShortString();
        HttpServletRequest request = currentRequest();
        String httpMethod = request != null ? request.getMethod() : UNKNOWN;
        // 쿼리 문자열은 남기지 않는다. 결제 콜백은 paymentKey·금액을 쿼리로 받는다.
        String path = request != null ? request.getRequestURI() : UNKNOWN;

        if (log.isDebugEnabled()) {
            log.debug("[API] {} {} handler={} args={}", httpMethod, path, handler, Arrays.toString(pjp.getArgs()));
        }

        StopWatch stopWatch = StopWatch.start();
        String outcome = OUTCOME_OK;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            outcome = t.getClass().getSimpleName();
            throw t;
        } finally {
            log.info("[API] {} {} handler={} elapsed={}ms outcome={}", httpMethod, path, handler, stopWatch.elapsed(), outcome);
        }
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }
}
