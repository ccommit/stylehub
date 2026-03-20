package ccommit.stylehub.order.enums;

import lombok.Getter;

@Getter
public enum DeliveryStatus {

    PREPARING,  // 배송 준비 중
    SHIPPING,   // 배송 중
    DELIVERED   // 배송 완료
}
