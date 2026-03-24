package ccommit.stylehub.common.util;

import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * @author WonJin Bae
 * @created 2026/03/23
 *
 * <p>
 * 세션 생성, 조회, 무효화를 담당하는 유틸 클래스이다.
 * 세션이 없거나 만료된 경우 BusinessException을 던진다.
 * </p>
 */
public final class SessionUtils {

    private SessionUtils() {}

    public static void createSession(HttpServletRequest request, Long userId, UserRole role) {
        // 세션 고정 공격(Session Fixation) 방지: 기존 세션 무효화 후 새 세션 생성
        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        HttpSession newSession = request.getSession(true);
        newSession.setAttribute(SessionConstants.SESSION_USER_ID, userId);
        newSession.setAttribute(SessionConstants.SESSION_USER_ROLE, role);
    }

    public static void invalidateSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    public static Long getUserId(HttpServletRequest request) {
        return getAttribute(request, SessionConstants.SESSION_USER_ID, Long.class);
    }

    public static UserRole getUserRole(HttpServletRequest request) {
        return getAttribute(request, SessionConstants.SESSION_USER_ROLE, UserRole.class);
    }

    private static <T> T getAttribute(HttpServletRequest request, String key, Class<T> type) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Object value = session.getAttribute(key);
        if (value == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return type.cast(value);
    }
}
