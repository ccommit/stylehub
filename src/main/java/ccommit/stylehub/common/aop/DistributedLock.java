package ccommit.stylehub.common.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * Redis 분산 락을 AOP로 투명하게 처리하는 커스텀 어노테이션이다.
 * 메서드 실행 전 락을 획득하고, 완료 후 자동 해제한다.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    //락 키. SpEL 표현식 지원. (예: "'stock:' + #productOptionId")
    String key();

    // 락 대기 시간(10초) 이 시간 내에 락을 획득하지 못하면 실패 (10초 내 락 못 잡으면 "잠시 후 다시 시도" 응답)
    long waitTime() default 10;

    // 락 점유 시간(15초) 이 시간이 지나면 자동 해제 (주문 처리가 확실히 끝나는 시간 + 안전 마진)
    long leaseTime() default 15;

    // 시간 단위
    TimeUnit timeUnit() default TimeUnit.SECONDS;
}
