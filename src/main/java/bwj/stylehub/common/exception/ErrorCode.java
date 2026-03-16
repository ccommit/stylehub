package bwj.stylehub.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력값입니다"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C002", "서버 내부 오류가 발생했습니다"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "지원하지 않는 HTTP 메서드입니다"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "C004", "요청한 리소스를 찾을 수 없습니다"),

    // User
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "U001", "이미 사용 중인 이메일입니다"),
    DUPLICATE_NAME(HttpStatus.CONFLICT, "U002", "이미 사용 중인 닉네임입니다"),
    DUPLICATE_EMAIL_OR_NAME(HttpStatus.CONFLICT, "U003", "이미 사용 중인 이메일 또는 닉네임입니다"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U004", "존재하지 않는 사용자입니다"),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "U005", "이메일 또는 비밀번호가 일치하지 않습니다"),

    // OAuth
    ALREADY_REGISTERED_EMAIL(HttpStatus.CONFLICT, "O001", "이미 일반 회원가입으로 등록된 이메일입니다"),
    ALREADY_REGISTERED_OTHER_PROVIDER(HttpStatus.CONFLICT, "O002", "이미 다른 소셜 계정으로 가입된 이메일입니다"),
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "O003", "지원하지 않는 OAuth Provider입니다"),
    OAUTH_AUTHENTICATION_FAILED(HttpStatus.BAD_GATEWAY, "O004", "OAuth 인증에 실패했습니다");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
