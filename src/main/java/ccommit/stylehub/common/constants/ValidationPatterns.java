package ccommit.stylehub.common.constants;

/**
 * @author WonJin Bae
 * @created 2026/03/21
 * @modified 2026/09/17 by WonJin - fix: 이름 허용 문자·길이 규칙을 상수로 분리 — 소셜 가입 닉네임 정규화가 일반 가입과 같은 규칙을 쓰도록
 *
 * <p>
 * 입력값 검증에 사용되는 정규식 패턴과 메시지를 관리한다.
 * 비밀번호 정책 변경 등 검증 기준이 바뀔 때 이 클래스만 수정하면 된다.
 * </p>
 */
public final class ValidationPatterns {

    private ValidationPatterns() {}

    // 이름: 한글, 알파벳, 숫자만 허용, 2~10자
    // 소셜 가입은 제공자가 준 표시 이름을 이 규칙에 맞춰 정규화하므로, 허용 문자와 길이를 한곳에서 관리한다.
    public static final String NAME_ALLOWED_CHARS = "가-힣a-zA-Z0-9";
    public static final String NAME_PATTERN = "^[" + NAME_ALLOWED_CHARS + "]+$";
    public static final String NAME_MESSAGE = "한글, 알파벳, 숫자만 허용됩니다";
    public static final int NAME_MIN_LENGTH = 2;
    public static final int NAME_MAX_LENGTH = 10;

    // 이메일
    public static final String EMAIL_PATTERN = ".+@.+\\..+";
    public static final String EMAIL_MESSAGE = "이메일 형식이 올바르지 않습니다";

    // 비밀번호: 영문, 숫자, 특수문자 각 1개 이상 포함, 8~15자
    public static final String PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,15}$";
    public static final String PASSWORD_MESSAGE = "비밀번호는 8~15자이며, 영문·숫자·특수문자(@$!%*?&)를 각각 1개 이상 포함해야 합니다";
}
