package ccommit.stylehub.common.util;

import ccommit.stylehub.common.constants.SessionConstants;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * OAuth 인가 요청의 state 값을 발급해 세션에 저장하고, 콜백에서 1회만 검증하는 유틸 클래스이다.
 * 인가 요청을 시작한 브라우저 세션과 콜백을 묶어, 공격자의 인가 코드로 피해자를 로그인시키는 로그인 CSRF 를 막는다.
 * </p>
 */
public final class OAuthStateUtils {

    private static final Logger log = LoggerFactory.getLogger(OAuthStateUtils.class);

    // 추측 불가능해야 하므로 SecureRandom 을 쓴다. SecureRandom 은 스레드 안전해 인스턴스 하나를 공유한다.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int STATE_BYTES = 32;
    // URL 쿼리에 그대로 실리므로 '+', '/', '=' 가 없는 base64url(패딩 없음)로 인코딩한다.
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OAuthStateUtils() {}

    // 같은 세션에서 다시 발급하면 이전 state는 덮어써져 무효가 된다
    public static String issue(HttpServletRequest request) {
        byte[] randomBytes = new byte[STATE_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String state = ENCODER.encodeToString(randomBytes);
        request.getSession(true).setAttribute(SessionConstants.SESSION_OAUTH_STATE, state);
        return state;
    }

    // 실패한 값으로 반복 시도하지 못하게 세션 값을 먼저 지우고, 응답 시간으로 일치 정도가 드러나지 않게 MessageDigest.isEqual로 비교한다.
    // 로그인 성공 시 SessionUtils.createSession이 기존 세션을 무효화하므로 그보다 먼저 호출해야 한다.
    public static void verifyAndConsume(HttpServletRequest request, String state) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            log.warn("OAuth state 검증 실패: 세션이 없습니다");
            throw new BusinessException(ErrorCode.INVALID_OAUTH_STATE);
        }

        Object expected = session.getAttribute(SessionConstants.SESSION_OAUTH_STATE);
        session.removeAttribute(SessionConstants.SESSION_OAUTH_STATE);

        if (!(expected instanceof String expectedState) || state == null) {
            log.warn("OAuth state 검증 실패: 발급된 값 또는 전달된 값이 없습니다");
            throw new BusinessException(ErrorCode.INVALID_OAUTH_STATE);
        }
        if (!MessageDigest.isEqual(
                expectedState.getBytes(StandardCharsets.UTF_8),
                state.getBytes(StandardCharsets.UTF_8))) {
            log.warn("OAuth state 검증 실패: 값이 일치하지 않습니다");
            throw new BusinessException(ErrorCode.INVALID_OAUTH_STATE);
        }
    }
}
