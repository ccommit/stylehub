package ccommit.stylehub.payment.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 결제 처리 상태를 정의한다.
 * 토스페이먼츠의 결제 상태 모델을 그대로 반영했다.
 * </p>
 */

@Getter
public enum PaymentStatus {

    READY,               // 결제 생성 후 초기 상태 (인증 전)
    IN_PROGRESS,         // 결제수단 인증 완료, 승인 API 호출 전
    WAITING_FOR_DEPOSIT, // 가상계좌 발급 후 입금 대기 중
    DONE,                // 결제 승인 완료
    CANCELED,            // 전체 취소
    PARTIAL_CANCELED,    // 부분 취소
    ABORTED,             // 결제 승인 실패
    EXPIRED              // 유효 시간 30분 초과로 거래 취소
}
