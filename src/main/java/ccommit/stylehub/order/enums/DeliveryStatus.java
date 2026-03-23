package ccommit.stylehub.order.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 배송 진행 상태(PREPARING/SHIPPING/DELIVERED)를 정의한다.
 * </p>
 */

@Getter
public enum DeliveryStatus {

    PREPARING,  // 배송 준비 중
    SHIPPING,   // 배송 중
    DELIVERED   // 배송 완료
}
