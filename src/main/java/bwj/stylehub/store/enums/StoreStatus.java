package bwj.stylehub.store.enums;

import lombok.Getter;

@Getter
public enum StoreStatus {

    PENDING,   // 입점 심사 중 (신청 직후 초기 상태)
    APPROVED,  // 입점 승인 완료
    SUSPENDED, // 스토어 운영 정지
    REJECTED   // 입점 거절
}