package ccommit.stylehub.order.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:15 by WonJin - refactor: orderstatus.  PREPARING,  // 배송 준비 중  삭제
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 주문 처리 상태(PENDING~CANCELLED)를 정의한다.
 * </p>
 */

@Getter
public enum OrderStatus {

    PENDING,    // 주문 완료 (결제 대기)
    PAID,       // 결제 완료
    SHIPPING,   // 배송 중
    DELIVERED,  // 배송 완료
    CANCELLED   // 주문 취소
}
