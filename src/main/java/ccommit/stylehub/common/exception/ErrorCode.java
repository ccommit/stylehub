package ccommit.stylehub.common.exception;

import org.springframework.http.HttpStatus;

/**
 * @author WonJin Bae
 * @created 2026/03/22
 * @modified 2026/03/24 by WonJin - refactor: bwj 패키지명 ccommit으로 변경, Auth 에러코드 추가
 *
 * <p>
 * 애플리케이션 전역에서 사용하는 에러 코드를 정의한다.
 * HttpStatus, 에러 코드, 메시지를 하나로 관리한다.
 * </p>
 */

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
    OAUTH_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "O004", "OAuth 인증에 실패했습니다"),

    // Store
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "S001", "존재하지 않는 스토어입니다"),
    STORE_ALREADY_EXISTS(HttpStatus.CONFLICT, "S002", "이미 입점 신청한 스토어가 존재합니다"),
    INVALID_STORE_STATUS(HttpStatus.BAD_REQUEST, "S003", "현재 상태에서는 처리할 수 없습니다"),
    UNAUTHORIZED_STORE_ACCESS(HttpStatus.FORBIDDEN, "S004", "본인 스토어만 접근할 수 있습니다"),

    // Product
    STORE_NOT_APPROVED(HttpStatus.FORBIDDEN, "P001", "입점 승인된 스토어만 상품을 등록할 수 있습니다"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "P002", "존재하지 않는 상품입니다"),
    INVALID_CATEGORY_COMBINATION(HttpStatus.BAD_REQUEST, "P003", "메인카테고리와 서브 카테고리가 일치하지 않습니다"),
    PRODUCT_OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "P004", "존재하지 않는 상품 옵션입니다"),

    // Order
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "OR001", "존재하지 않는 주문입니다"),
    INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "OR002", "현재 상태에서는 처리할 수 없습니다"),
    UNAUTHORIZED_ORDER_ACCESS(HttpStatus.FORBIDDEN, "OR003", "본인 주문만 접근할 수 있습니다"),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT, "OR004", "재고가 부족합니다"),
    ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "OR005", "존재하지 않는 배송지입니다"),
    INVALID_DELIVERY_STATUS(HttpStatus.BAD_REQUEST, "OR006", "잘못된 배송 상태 전이입니다"),
    UNAUTHORIZED_DELIVERY_ACCESS(HttpStatus.FORBIDDEN, "OR007", "본인 스토어 주문의 배송 상태만 변경할 수 있습니다"),

    // Payment
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PM001", "존재하지 않는 결제입니다"),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "PM002", "결제 금액이 일치하지 않습니다"),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "PM003", "이미 처리된 결제입니다"),
    PAYMENT_APPROVAL_FAILED(HttpStatus.BAD_GATEWAY, "PM004", "토스페이먼츠 결제 승인에 실패했습니다"),
    PAYMENT_CANCEL_FAILED(HttpStatus.BAD_GATEWAY, "PM005", "토스페이먼츠 결제 취소에 실패했습니다"),
    INVALID_CANCEL_AMOUNT(HttpStatus.BAD_REQUEST, "PM006", "취소 금액이 잔액을 초과합니다"),
    CANCEL_NOT_ALLOWED_SHIPPING(HttpStatus.BAD_REQUEST, "PM007", "배송 중에는 취소할 수 없습니다"),
    REFUND_PERIOD_EXPIRED(HttpStatus.BAD_REQUEST, "PM008", "환불 가능 기간이 지났습니다"),

    // Coupon
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "CP001", "존재하지 않는 쿠폰 이벤트입니다"),
    COUPON_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "CP002", "비활성화된 쿠폰 이벤트입니다"),
    COUPON_NOT_STARTED(HttpStatus.BAD_REQUEST, "CP003", "아직 시작되지 않은 쿠폰 이벤트입니다"),
    COUPON_EXPIRED(HttpStatus.BAD_REQUEST, "CP004", "만료된 쿠폰 이벤트입니다"),
    COUPON_SOLD_OUT(HttpStatus.CONFLICT, "CP005", "쿠폰이 모두 소진되었습니다"),
    COUPON_ALREADY_ISSUED(HttpStatus.CONFLICT, "CP006", "이미 발급받은 쿠폰입니다"),
    INVALID_DISCOUNT_VALUE(HttpStatus.BAD_REQUEST, "CP007", "할인 값이 유효하지 않습니다"),
    INVALID_COUPON_PERIOD(HttpStatus.BAD_REQUEST, "CP008", "쿠폰 유효기간이 올바르지 않습니다"),
    INVALID_COUPON_TYPE(HttpStatus.BAD_REQUEST, "CP009", "쿠폰 타입과 스토어 설정이 일치하지 않습니다"),

    // Auth
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A001", "로그인이 필요합니다"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "A002", "접근 권한이 없습니다"),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED, "A003", "세션이 만료되었습니다");

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
