package ccommit.stylehub.product.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 상품 대분류(SHOES/TOP/BOTTOM/ACCESSORY)를 정의한다.
 * </p>
 */

@Getter
public enum MainCategory {

    SHOES, // 신발
    TOP,   // 상의
    BOTTOM, // 하의
    ACCESSORY // 악세사리
}
