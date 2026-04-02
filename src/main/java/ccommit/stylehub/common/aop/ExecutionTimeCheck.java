package ccommit.stylehub.common.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * 메서드 실행 시간을 측정하는 커스텀 어노테이션이다.
 * 임계값 초과 시 경고 로그를 남긴다.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExecutionTimeCheck {

    // 슬로우 판정 임계값 (ms). 기본 1000ms.
    long threshold() default 1000;
}
