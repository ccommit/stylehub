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
 * 상품 재고 등 다수의 유저가 동시에 write할 때 데이터 불일치를 방지하기 위해 사용한다.
 * 메서드 실행 전 락을 획득하고, 완료 후 자동 해제한다.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    //락 키. SpEL 표현식 지원. (예: "'stock:' + #productOptionstock:' + #productOptionId")Id")
    String key();

    // 락 대기 시간(3초) 이 시간 내에 락을 획득하지 못하면 실패
    long waitTime() default 3000;

    // 락 점유 시간(5초) 이 시간이 지나면 자동 해제. DB 지연/GC 고려하여 작업 시간의 3~5배로 설정.
    long leaseTime() default 5000;

    // 시간 단위
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;
}
