package ccommit.stylehub.common.constants;

/**
 * @author WonJin Bae
 * @created 2026/03/21
 *
 * <p>
 * 입력값 검증에 사용되는 정규식 패턴과 메시지를 관리한다.
 * 비밀번호 정책 변경 등 검증 기준이 바뀔 때 이 클래스만 수정하면 된다.
 * </p>
 */
public final class ValidationPatterns {

    private ValidationPatterns() {}

    // 이름: 한글, 알파벳, 숫자만 허용
    public static final String NAME_PATTERN = "^[가-힣a-zA-Z0-9]+$";
    public static final String NAME_MESSAGE = "한글, 알파벳, 숫자만 허용됩니다";

    // 이메일
    public static final String EMAIL_PATTERN = ".+@.+\\..+";
    public static final String EMAIL_MESSAGE = "이메일 형식이 올바르지 않습니다";

    // 비밀번호: 영문, 숫자, 특수문자 각 1개 이상 포함, 8~15자
    public static final String PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,15}$";
    public static final String PASSWORD_MESSAGE = "비밀번호는 8~15자이며, 영문·숫자·특수문자(@$!%*?&)를 각각 1개 이상 포함해야 합니다";
}
