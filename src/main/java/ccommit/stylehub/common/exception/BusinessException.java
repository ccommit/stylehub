package ccommit.stylehub.common.exception;

/**
 * @author WonJin Bae
 * @created 2026/03/22
 * @modified 2026/03/24 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/09/17 by WonJin - feat: 원인 예외를 보존하는 생성자 추가 (인프라 장애를 비즈니스 응답으로 바꿀 때 원인 추적용)
 *
 * <p>
 * 비즈니스 로직에서 발생하는 예외를 표현하는 커스텀 런타임 예외이다.
 * ErrorCode를 감싸 GlobalExceptionHandler에서 일관된 에러 응답을 생성한다.
 * </p>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    // Redis 장애처럼 인프라 예외를 비즈니스 응답 코드로 바꿀 때, 원인 예외를 잃지 않도록 cause 로 보존한다.
    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
