package ccommit.stylehub.common.aop;

import ccommit.stylehub.common.util.StopWatch;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/01 by WonJin - feat: finally 블록에 종료 로그 추가
 *
 * <p>
 * 주문/결제 API의 요청/응답을 자동으로 로깅하는 AOP이다.
 * 서비스 코드를 오염시키지 않고 모든 호출을 추적할 수 있다.
 * </p>
 */
@Aspect
@Component
public class ApiLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(ApiLoggingAspect.class);

    @Around("execution(* ccommit.stylehub.order..*(..))" +
            " || execution(* ccommit.stylehub.payment..*(..))")
    public Object logOrderAndPayment(ProceedingJoinPoint pjp) throws Throwable {
        String method = pjp.getSignature().toShortString();
        Object[] args = pjp.getArgs();

        log.info("[REQUEST] {} args={}", method, Arrays.toString(args));

        StopWatch stopWatch = StopWatch.start();
        boolean success = false;
        try {
            Object result = pjp.proceed();
            success = true;
            log.info("[RESPONSE] {} elapsed={}ms", method, stopWatch.elapsed());
            return result;
        } catch (Exception e) {
            log.error("[ERROR] {} elapsed={}ms error={}", method, stopWatch.elapsed(), e.getMessage());
            throw e;
        } finally {
            log.info("[COMPLETE] {} elapsed={}ms success={}", method, stopWatch.elapsed(), success);
        }
    }
}
