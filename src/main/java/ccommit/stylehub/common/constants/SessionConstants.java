package ccommit.stylehub.common.constants;

/**
 * @author WonJin Bae
 * @created 2026/03/23
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
}
