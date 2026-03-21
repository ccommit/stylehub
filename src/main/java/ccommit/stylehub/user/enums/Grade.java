package ccommit.stylehub.user.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/16 18:16 by WonJin - feat: 회원 API 개발 (회원가입, 로그인, 구글 OAuth, 포인트 지급)
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 회원 등급별 할인율(BRONZE 2%/SILVER 4%/GOLD 7%)을 정의한다.
 * </p>
 */

@Getter
public enum Grade {

    BRONZE(2),
    SILVER(4),
    GOLD(7);

    private final int discountRate;

    Grade(int discountRate) {
        this.discountRate = discountRate;
    }
}
