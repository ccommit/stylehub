package ccommit.stylehub.common.aop;

import ccommit.stylehub.common.util.StopWatch;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * @ExecutionTimeCheck 어노테이션이 붙은 메서드의 실행 시간을 측정한다.
 * 임계값을 초과하면 WARN 로그를 남겨 슬로우 메서드를 감지한다.
 * </p>
 */
@Aspect
@Component
public class ExecutionTimeAspect {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTimeAspect.class);

    @Around("@annotation(executionTimeCheck)")
    public Object checkExecutionTime(ProceedingJoinPoint pjp, ExecutionTimeCheck executionTimeCheck) throws Throwable {
        StopWatch stopWatch = StopWatch.start();
        Object result = pjp.proceed();

        String method = pjp.getSignature().toShortString();
        if (stopWatch.elapsed() > executionTimeCheck.threshold()) {
            log.warn("[SLOW] {} elapsed={}ms threshold={}ms", method, stopWatch.elapsed(), executionTimeCheck.threshold());
        }

        return result;
    }
}
