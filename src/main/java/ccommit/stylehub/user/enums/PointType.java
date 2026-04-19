package ccommit.stylehub.user.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 포인트 변동 유형(EARN/USE/WELCOME/DAILY_LOGIN)을 정의한다.
 * </p>
 */

@Getter
public enum PointType {

    EARN,        // 구매 확정 시 포인트 적립
    USE,         // 주문 시 포인트 차감
    WELCOME,     // 첫 로그인 웰컴 포인트 (1000P)
    DAILY_LOGIN  // 일일 로그인 포인트 (10P, 하루 1회)
}
