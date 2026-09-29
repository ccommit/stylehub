package ccommit.stylehub.payment.enums;

/**
 * @author WonJin Bae
 * @created 2026/09/29
 *
 * <p>
 * 자동 환불에 실패한 승인 건의 처리 상태를 정의한다.
 * </p>
 */
public enum RefundFailureStatus {

    PENDING,  // 환불 재시도 대기
    RESOLVED  // 재시도로 환불 완료
}
