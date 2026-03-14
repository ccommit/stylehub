package bwj.stylehub.order.enums;

import lombok.Getter;

@Getter
public enum OrderStatus {

    PENDING,    // 주문 완료 (결제 대기)
    PAID,       // 결제 완료
    PREPARING,  // 배송 준비 중
    SHIPPING,   // 배송 중
    DELIVERED,  // 배송 완료
    CANCELLED   // 주문 취소
}
