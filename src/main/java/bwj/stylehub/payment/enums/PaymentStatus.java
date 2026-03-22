package bwj.stylehub.payment.enums;

import lombok.Getter;

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
