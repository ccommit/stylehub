package ccommit.stylehub.store.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 스토어 입점 상태(PENDING/APPROVED/SUSPENDED/REJECTED)를 정의한다.
 * </p>
 */

@Getter
public enum StoreStatus {

    PENDING,   // 입점 심사 중 (신청 직후 초기 상태)
    APPROVED,  // 입점 승인 완료
    SUSPENDED, // 스토어 운영 정지
    REJECTED   // 입점 거절
}