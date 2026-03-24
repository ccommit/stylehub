package ccommit.stylehub.common.config;

import ccommit.stylehub.user.enums.UserRole;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author WonJin Bae
 * @created 2026/03/23
 *
 * <p>
 * 컨트롤러 메서드에 필요한 역할을 지정하는 어노테이션이다.
 * RoleCheckInterceptor가 이 어노테이션을 읽어 역할 검증을 수행한다.
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiredRole {

    UserRole[] value();
}
