package ccommit.stylehub.coupon.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 쿠폰의 할인 방식(FIXED 정액/RATE 정률)을 정의한다.
 * </p>
 */

@Getter
public enum DiscountType {
    FIXED, RATE
}
