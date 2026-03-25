package ccommit.stylehub.common.config;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * @author WonJin Bae
 * @created 2026/03/23
 *
 * <p>
 * 컨트롤러 클래스 또는 메서드에 @RequiredRole이 선언된 경우 세션의 역할 정보를 검증하는 인터셉터이다.
 * 메서드 레벨이 클래스 레벨보다 우선 적용된다.
 * 요구 역할과 일치하지 않으면 FORBIDDEN 예외를 던진다.
 * </p>
 */
@Component
public class RoleCheckInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 메서드 레벨 우선, 없으면 클래스 레벨 확인
        RequiredRole requiredRole = handlerMethod.getMethodAnnotation(RequiredRole.class);
        if (requiredRole == null) {
            requiredRole = handlerMethod.getBeanType().getAnnotation(RequiredRole.class);
        }
        if (requiredRole == null) {
            return true;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        UserRole userRole = (UserRole) session.getAttribute(SessionConstants.SESSION_USER_ROLE);
        if (userRole == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        boolean hasRole = Arrays.asList(requiredRole.value()).contains(userRole);
        if (!hasRole) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        return true;
    }
}
