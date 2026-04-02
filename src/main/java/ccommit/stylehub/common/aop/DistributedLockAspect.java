package ccommit.stylehub.common.aop;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * @DistributedLock 어노테이션이 붙은 메서드에 Redis 분산 락을 적용하는 AOP이다.
 * RedisTemplate의 setIfAbsent(SETNX)로 락 획득/해제를 처리한다.
 * SpEL로 동적 락 키를 지원한다.
 * </p>
 */
@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockAspect.class);
    private static final String LOCK_PREFIX = "lock:";

    private final StringRedisTemplate redisTemplate;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>();

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint pjp, DistributedLock distributedLock) throws Throwable {
        String key = LOCK_PREFIX + resolveKey(pjp, distributedLock.key());
        String value = UUID.randomUUID().toString();
        Duration leaseTime = Duration.of(distributedLock.leaseTime(), distributedLock.timeUnit().toChronoUnit());

        boolean acquired = tryLock(key, value, leaseTime, distributedLock.waitTime(), distributedLock.timeUnit());

        if (!acquired) {
            log.warn("분산 락 획득 실패: {}", key);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        try {
            log.debug("분산 락 획득: {}", key);
            return pjp.proceed();
        } finally {
            unlock(key, value);
            log.debug("분산 락 해제: {}", key);
        }
    }

    private boolean tryLock(String key, String value, Duration leaseTime, long waitTime, java.util.concurrent.TimeUnit timeUnit) {
        long deadline = System.currentTimeMillis() + timeUnit.toMillis(waitTime);

        while (System.currentTimeMillis() < deadline) {
            Boolean success = redisTemplate.opsForValue().setIfAbsent(key, value, leaseTime);
            if (Boolean.TRUE.equals(success)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void unlock(String key, String value) {
        String currentValue = redisTemplate.opsForValue().get(key);
        if (value.equals(currentValue)) {
            redisTemplate.delete(key);
        }
    }

    private String resolveKey(ProceedingJoinPoint pjp, String keyExpression) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String[] paramNames = nameDiscoverer.getParameterNames(signature.getMethod());
        Object[] args = pjp.getArgs();

        EvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }

        Expression expression = expressionCache.computeIfAbsent(keyExpression, parser::parseExpression);
        return expression.getValue(context, String.class);
    }
}
