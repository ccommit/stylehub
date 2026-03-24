package ccommit.stylehub.common.config;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.common.constants.SessionConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * @author WonJin Bae
 * @created 2026/03/23
 *
 * <p>
 * 인증이 필요한 API 요청에서 세션 존재 여부를 검증하는 인터셉터이다.
 * 세션이 없거나 사용자 정보가 없으면 UNAUTHORIZED 예외를 던진다.
 * </p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SessionConstants.SESSION_USER_ID) == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return true;
    }
}
