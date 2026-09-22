package ccommit.stylehub.common.exception;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * @author WonJin Bae
 * @created 2026/03/22
 * @modified 2026/03/24 by WonJin - refactor: bwj 패키지명 ccommit으로 변경, log.warn/error 분리, timestamp/path/traceId 추가
 * @modified 2026/09/17 by WonJin - refactor: 무결성 위반·락 획득 실패 409, Redis 일시 장애 503, 미디어 타입 415, 헤더·경로 변수 누락 매핑 추가, 병합 충돌 잔재 제거
 *
 * <p>
 * 애플리케이션 전역 예외를 처리하는 핸들러이다.
 * 비즈니스 예외는 warn, 시스템 예외는 error로 로그 레벨을 분리한다.
 * </p>
 *
 * <p>
 * 예외 로그는 이 클래스 한곳에서 요청당 한 번만 남긴다(컨트롤러 AOP·서비스에서 같은 예외를 다시 기록하지 않는다).
 * 원인이 클라이언트 요청이나 현재 데이터 상태에 있어 4xx 로 응답하는 예외는 warn, 서버 결함·인프라 장애로 5xx 를 응답하는 예외는 error 다.
 * 로그 줄의 traceId 는 TraceIdFilter 가 MDC 에 넣은 값이며, 에러 응답의 traceId 와 같다.
 * </p>
 *
 * <p>
 * 스프링은 던져진 예외 타입과 가장 가까운 @ExceptionHandler 를 고르므로, 아래 구체 타입 핸들러가 Exception 핸들러보다 우선한다.
 * 비즈니스 예외가 인프라 예외를 cause 로 감싸도(예: 선착순 쿠폰 CP016) 최상위 타입인 BusinessException 핸들러가 처리한다.
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 비즈니스 예외 — throw new BusinessException(ErrorCode.XXX)
    @ExceptionHandler(BusinessException.class)
    protected ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e, HttpServletRequest request) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("Business exception: {} {} {}", request.getMethod(), request.getRequestURI(), errorCode);
        return respond(errorCode, request);
    }

    // Bean Validation 실패 — @Valid 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    protected ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ErrorCode.INVALID_INPUT.getMessage());

        return respond(ErrorCode.INVALID_INPUT, message, request);
    }

    // PathVariable, RequestParam 타입 변환 실패
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    protected ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return respond(ErrorCode.INVALID_INPUT, request);
    }

    // 필수 RequestParam 누락 — ?code= 없이 요청
    @ExceptionHandler(MissingServletRequestParameterException.class)
    protected ResponseEntity<ErrorResponse> handleMissingServletRequestParameter(MissingServletRequestParameterException e, HttpServletRequest request) {
        String message = e.getParameterName() + " 파라미터가 필요합니다";
        return respond(ErrorCode.INVALID_INPUT, message, request);
    }

    // 필수 요청 헤더 누락 — @RequestHeader 로 선언한 헤더 없이 요청
    @ExceptionHandler(MissingRequestHeaderException.class)
    protected ResponseEntity<ErrorResponse> handleMissingRequestHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        log.warn("필수 요청 헤더 누락: {} {} header={}", request.getMethod(), request.getRequestURI(), e.getHeaderName());
        return respond(ErrorCode.INVALID_INPUT, e.getHeaderName() + " 헤더가 필요합니다", request);
    }

    /*
     * 경로 변수 누락.
     * URL 이 매핑에 맞았는데 경로 변수가 없다는 것은 @GetMapping 의 URI 템플릿과 @PathVariable 이름이 어긋난 선언 실수라,
     * 클라이언트가 고칠 수 없는 서버 결함이다. 값은 있었지만 변환 결과가 null 인 경우만 클라이언트 입력 문제다.
     * 스프링도 같은 기준으로 앞의 경우를 500, 뒤의 경우를 400 으로 분류한다(MissingPathVariableException#getStatusCode).
     */
    @ExceptionHandler(MissingPathVariableException.class)
    protected ResponseEntity<ErrorResponse> handleMissingPathVariable(MissingPathVariableException e, HttpServletRequest request) {
        if (e.isMissingAfterConversion()) {
            log.warn("경로 변수 변환 결과 없음: {} {} variable={}", request.getMethod(), request.getRequestURI(), e.getVariableName());
            return respond(ErrorCode.INVALID_INPUT, e.getVariableName() + " 경로 값이 올바르지 않습니다", request);
        }
        log.error("경로 변수 매핑 선언 오류: {} {} variable={}, handler={}",
                request.getMethod(), request.getRequestURI(), e.getVariableName(), e.getParameter().getExecutable());
        return respond(ErrorCode.INTERNAL_SERVER_ERROR, request);
    }

    // JSON 파싱 실패 — 잘못된 JSON body, 타입 불일치
    @ExceptionHandler(HttpMessageNotReadableException.class)
    protected ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return respond(ErrorCode.INVALID_INPUT, "요청 본문을 읽을 수 없습니다", request);
    }

    // 지원하지 않는 Content-Type — JSON 을 받는 API 에 text/plain 등으로 요청
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    protected ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        log.warn("지원하지 않는 Content-Type: {} {} contentType={}", request.getMethod(), request.getRequestURI(), e.getContentType());
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, request);
    }

    // 지원하지 않는 HTTP 메서드 — POST 엔드포인트에 GET 요청
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    protected ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        String message = e.getMethod() + " 메서드는 지원하지 않습니다";
        return respond(ErrorCode.METHOD_NOT_ALLOWED, message, request);
    }

    // 존재하지 않는 API 경로
    @ExceptionHandler(NoResourceFoundException.class)
    protected ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, request);
    }

    /*
     * 유니크·외래키·NOT NULL 제약 위반.
     * 서비스가 미리 중복을 확인해도 동시 요청 둘이 함께 통과하면 DB 제약에서 걸린다. 요청을 다시 보내면 결과가 달라질 수 있는
     * 현재 데이터와의 충돌이므로 409 로 응답한다. 도메인에서 의미를 아는 위반(배송지 삭제 FK, 쿠폰 중복 발급)은 각 서비스가 먼저 바꾼다.
     * 응답에는 제약 이름·SQL 을 넣지 않는다(스키마 구조 노출). 로그에도 DB 메시지는 남기지 않는데, MySQL 중복 메시지에는
     * 입력값(이메일 등)이 그대로 들어 있기 때문이다. 원인 파악은 제약 이름으로 한다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    protected ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException e, HttpServletRequest request) {
        log.warn("데이터 무결성 제약 위반: {} {} {}", request.getMethod(), request.getRequestURI(), describeConstraint(e));
        return respond(ErrorCode.DATA_INTEGRITY_CONFLICT, request);
    }

    /*
     * 비관적 락 대기 시간 초과·데드락 희생.
     * 스프링 리포지토리를 거치면 PessimisticLockingFailureException(락 대기 초과·데드락은 하위 타입 CannotAcquireLockException)으로
     * 변환되지만, 서비스에서 EntityManager 로 직접 락을 거는 경로(PaymentService 의 주문 잠금 조회, CouponService 의 이벤트 refresh)는 예외 변환 대상이 아니라
     * JPA 표준 예외(PessimisticLockException, LockTimeoutException)가 그대로 올라오므로 함께 받는다.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, PessimisticLockException.class, LockTimeoutException.class})
    protected ResponseEntity<ErrorResponse> handleLockAcquisitionFailure(RuntimeException e, HttpServletRequest request) {
        log.warn("락 획득 실패: {} {} cause={}", request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName());
        return respond(ErrorCode.LOCK_ACQUISITION_FAILED, request);
    }

    /*
     * Redis 연결 실패·명령 타임아웃(세션 조회, 상품 캐시 등 도메인이 따로 처리하지 않는 경로).
     * Lettuce 명령 타임아웃은 QueryTimeoutException 으로 변환된다. DB 쿼리 타임아웃도 같은 타입이라 함께 503 이 되는데,
     * 둘 다 재시도로 회복될 수 있는 일시 장애라는 점이 같다. 스크립트 오류(RedisSystemException) 같은 코드 결함은 500 으로 남긴다.
     * 장애 중에는 모든 요청이 같은 예외를 내므로 스택 트레이스 없이 원인 한 줄만 남긴다(CouponIssueCounter 와 같은 기준).
     */
    @ExceptionHandler({RedisConnectionFailureException.class, QueryTimeoutException.class})
    protected ResponseEntity<ErrorResponse> handleTemporarilyUnavailable(DataAccessException e, HttpServletRequest request) {
        log.error("일시 장애로 요청 처리 실패: {} {} cause={}", request.getMethod(), request.getRequestURI(), e.toString());
        return respond(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE, request);
    }

    // 예상치 못한 모든 예외
    @ExceptionHandler(Exception.class)
    protected ResponseEntity<ErrorResponse> handleException(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(ErrorCode.INTERNAL_SERVER_ERROR, request);
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode errorCode, HttpServletRequest request) {
        return respond(errorCode, errorCode.getMessage(), request);
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode errorCode, String message, HttpServletRequest request) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, message, request.getRequestURI()));
    }

    // 하이버네이트가 제약 이름을 알아낸 경우 이름과 종류를, 아니면 원인 예외 타입만 돌려준다.
    private static String describeConstraint(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation) {
                return "constraint=" + violation.getConstraintName() + ", kind=" + violation.getKind();
            }
            cause = cause.getCause();
        }
        return "cause=" + e.getMostSpecificCause().getClass().getSimpleName();
    }
}
