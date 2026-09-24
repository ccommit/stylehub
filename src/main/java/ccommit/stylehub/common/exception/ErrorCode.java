package ccommit.stylehub.common.exception;

import org.springframework.http.HttpStatus;

/**
 * @author WonJin Bae
 * @created 2026/03/22
 * @modified 2026/03/24 by WonJin - refactor: bwj 패키지명 ccommit으로 변경, Auth 에러코드 추가
 * @modified 2026/09/17 by WonJin - feat: UNAUTHORIZED_PAYMENT_ACCESS 추가 (타인 결제 취소 차단)
 * @modified 2026/09/17 by WonJin - feat: ORDER_NOT_PAYABLE, PAYMENT_RESULT_UNKNOWN 추가 (만료 주문 승인 차단, PG 결과 불명 구분)
 * @modified 2026/09/17 by WonJin - feat: COUPON_NOT_APPLICABLE 추가 (발행 스토어 상품이 없는 주문의 스토어 쿠폰 사용 거절)
 * @modified 2026/09/17 by WonJin - fix: PRODUCT_NOT_ON_SALE 추가 (승인 상태가 아닌 스토어 상품 주문 거절)
 * @modified 2026/09/17 by WonJin - fix: 발급 수량 축소 오류(CP014)와 Redis 장애 시 선착순 발급 일시 중단(CP016, 503) 코드 추가
 * @modified 2026/09/17 by WonJin - fix: OAuth state 불일치·소셜 가입 닉네임 충돌·OAuth 제공자 통신 실패 코드 추가 — 500 으로 뭉치던 클라이언트 오류와 외부 장애를 구분
 * @modified 2026/09/17 by WonJin - feat: 배송지 개수 초과(U006)·주문에 사용 중인 배송지 삭제(U007) 에러코드 추가
 * @modified 2026/09/17 by WonJin - refactor: 무결성 위반(C005)·락 획득 실패(C006)·일시 장애(C007)·미지원 미디어 타입(C008) 코드 추가 — 500 으로 뭉치던 프레임워크 예외를 원인별로 구분
 * @modified 2026/09/17 by WonJin - feat: 주문 포인트 사용 거절 코드 추가 — 잔액 부족(U008), 최소 주문 금액 미달(U009), 결제 금액 이상 사용(U010)
 * @modified 2026/09/18 by WonJin - feat: Idempotency-Key 형식 오류(C009)·다른 요청에 키 재사용(C010, 422)·같은 키 처리 중(C011) 코드 추가
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
    // 제약 이름·SQL 은 스키마 구조를 드러내므로 메시지에 담지 않는다. 동시 요청이 같은 값을 넣는 경합처럼 현재 데이터와의 충돌이라 409.
    DATA_INTEGRITY_CONFLICT(HttpStatus.CONFLICT, "C005", "요청이 현재 데이터와 충돌해 처리할 수 없습니다"),
    // 요청은 올바르지만 같은 데이터를 다른 요청이 잠그고 있어 처리하지 못했다. 잠시 뒤 재시도하면 성공할 수 있다.
    LOCK_ACQUISITION_FAILED(HttpStatus.CONFLICT, "C006", "다른 요청이 같은 데이터를 처리하고 있습니다. 잠시 후 다시 시도해주세요"),
    // Redis 연결 실패·명령 타임아웃처럼 재시도하면 회복될 수 있는 장애. 코드 결함(500)과 구분해 클라이언트가 재시도를 판단하게 한다.
    SERVICE_TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "C007", "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해주세요"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "C008", "지원하지 않는 Content-Type 입니다"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "C009", "Idempotency-Key 는 영문·숫자·-·_ 로 된 64자 이하 값이어야 합니다"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "C010", "이미 다른 요청에 사용한 Idempotency-Key 입니다"),
    // 앞선 같은 키 요청이 아직 커밋 전이라 결과를 돌려줄 수 없다. C006 과 달리 같은 키의 중복 요청에만 쓴다.
    IDEMPOTENT_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "C011", "같은 Idempotency-Key 요청을 처리하고 있습니다. 잠시 후 다시 시도해주세요"),

    // User
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "U001", "이미 사용 중인 이메일입니다"),
    DUPLICATE_NAME(HttpStatus.CONFLICT, "U002", "이미 사용 중인 닉네임입니다"),
    DUPLICATE_EMAIL_OR_NAME(HttpStatus.CONFLICT, "U003", "이미 사용 중인 이메일 또는 닉네임입니다"),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U004", "존재하지 않는 사용자입니다"),
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "U005", "이메일 또는 비밀번호가 일치하지 않습니다"),
    // 입력값 자체는 유효하고 "이미 5개 보유"라는 현재 상태 때문에 거절되므로 400 이 아닌 409 로 둔다 (INSUFFICIENT_STOCK, COUPON_SOLD_OUT 과 같은 기준)
    ADDRESS_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "U006", "배송지는 최대 5개까지 등록할 수 있습니다"),
    ADDRESS_IN_USE(HttpStatus.CONFLICT, "U007", "주문에 사용된 배송지는 삭제할 수 없습니다"),
    // 요청 값은 유효하지만 "현재 잔액"이라는 상태 때문에 거절되므로 409 (INSUFFICIENT_STOCK 과 같은 기준)
    INSUFFICIENT_POINT(HttpStatus.CONFLICT, "U008", "보유 포인트가 부족합니다"),
    // 요청 자체(주문 항목·사용 포인트)만으로 판정되는 규칙 위반이라 400 (MIN_ORDER_AMOUNT_NOT_MET, INVALID_CANCEL_AMOUNT 와 같은 기준)
    // 금액은 Order.MIN_ORDER_AMOUNT_FOR_POINT 와 함께 바꿔야 한다
    POINT_MIN_ORDER_AMOUNT_NOT_MET(HttpStatus.BAD_REQUEST, "U009", "상품 금액 합계가 10,000원 이상인 주문에만 포인트를 사용할 수 있습니다"),
    POINT_EXCEEDS_PAYMENT_AMOUNT(HttpStatus.BAD_REQUEST, "U010", "사용 포인트는 쿠폰 할인 후 결제 금액보다 적어야 합니다"),

    // OAuth
    ALREADY_REGISTERED_EMAIL(HttpStatus.CONFLICT, "O001", "이미 일반 회원가입으로 등록된 이메일입니다"),
    ALREADY_REGISTERED_OTHER_PROVIDER(HttpStatus.CONFLICT, "O002", "이미 다른 소셜 계정으로 가입된 이메일입니다"),
    UNSUPPORTED_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "O003", "지원하지 않는 OAuth Provider입니다"),
    OAUTH_AUTHENTICATION_FAILED(HttpStatus.UNAUTHORIZED, "O004", "OAuth 인증에 실패했습니다"),
    INVALID_OAUTH_STATE(HttpStatus.BAD_REQUEST, "O005", "OAuth 로그인 요청이 유효하지 않습니다. 로그인을 다시 시작해주세요"),
    OAUTH_NICKNAME_CONFLICT(HttpStatus.CONFLICT, "O006", "소셜 가입 닉네임을 정하지 못했습니다. 잠시 후 다시 시도해주세요"),
    OAUTH_PROVIDER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "O007", "OAuth 제공자와 통신하지 못했습니다. 잠시 후 다시 시도해주세요"),

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
    // 요청 형식은 올바르고 상품도 존재하지만, 스토어가 정지·미승인이라는 "현재 상태" 와 충돌해 처리할 수 없으므로 409.
    // 입력값 오류(400)가 아니며, 같은 요청이 스토어 상태에 따라 성공할 수도 있다는 점에서 INSUFFICIENT_STOCK(409)과 같은 성격이다.
    PRODUCT_NOT_ON_SALE(HttpStatus.CONFLICT, "P005", "현재 판매 중이 아닌 상품입니다"),

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
    UNAUTHORIZED_PAYMENT_ACCESS(HttpStatus.FORBIDDEN, "PM009", "본인 결제만 취소할 수 있습니다"),
    ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "PM010", "만료되었거나 취소된 주문은 결제할 수 없습니다"),
    PAYMENT_RESULT_UNKNOWN(HttpStatus.BAD_GATEWAY, "PM011", "결제 결과를 확인하고 있습니다. 잠시 후 주문 상태를 확인해주세요"),

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
    USER_COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "CP010", "존재하지 않는 보유 쿠폰입니다"),
    COUPON_NOT_AVAILABLE(HttpStatus.CONFLICT, "CP011", "사용 가능한 상태의 쿠폰이 아닙니다"),
    MIN_ORDER_AMOUNT_NOT_MET(HttpStatus.BAD_REQUEST, "CP012", "최소 주문 금액 미달로 쿠폰을 사용할 수 없습니다"),
    UNAUTHORIZED_USER_COUPON(HttpStatus.FORBIDDEN, "CP013", "본인의 쿠폰이 아닙니다"),
    INVALID_ISSUE_COUNT(HttpStatus.BAD_REQUEST, "CP014", "이미 발급된 수량보다 적게 변경할 수 없습니다"),
    COUPON_NOT_APPLICABLE(HttpStatus.BAD_REQUEST, "CP015", "쿠폰을 발행한 스토어의 상품이 주문에 없습니다"),
    COUPON_ISSUE_TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "CP016", "선착순 쿠폰 발급을 잠시 처리할 수 없습니다. 잠시 후 다시 시도해주세요"),

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
