package ccommit.stylehub.common.constants;

/**
 * @author WonJin Bae
 * @created 2026/03/23
 * @modified 2026/09/17 by WonJin - fix: OAuth 로그인 CSRF 방지용 state 세션 키 추가
 *
 * <p>
 * HTTP 세션에 저장되는 attribute key 상수를 관리한다.
 * 세션 key 변경 시 이 클래스만 수정하면 된다.
 * </p>
 */
public final class SessionConstants {

    private SessionConstants() {}

    public static final String SESSION_USER_ID = "SESSION_USER_ID";
    public static final String SESSION_USER_ROLE = "SESSION_USER_ROLE";

    // OAuth 인가 요청과 콜백을 같은 브라우저 세션으로 묶는 1회용 값. 콜백에서 검증 즉시 제거한다.
    public static final String SESSION_OAUTH_STATE = "SESSION_OAUTH_STATE";
}
