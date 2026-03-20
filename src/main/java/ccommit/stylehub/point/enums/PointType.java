package ccommit.stylehub.point.enums;

import lombok.Getter;

@Getter
public enum PointType {

    EARN,        // 구매 확정 시 포인트 적립
    USE,         // 주문 시 포인트 차감
    WELCOME,     // 첫 로그인 웰컴 포인트 (1000P)
    DAILY_LOGIN  // 일일 로그인 포인트 (10P, 하루 1회)
}
