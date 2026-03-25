package ccommit.stylehub.product.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/03/25 by WonJin - feat: MainCategory 매핑 추가
 *
 * <p>
 * 상품 소분류를 정의한다.
 * 대분류별 3개씩 총 12개의 소분류로 구성된다.
 * </p>
 */

@Getter
public enum SubCategory {

    // 신발
    SNEAKERS(MainCategory.SHOES),
    DRESS_SHOES(MainCategory.SHOES),
    RUNNING_SHOES(MainCategory.SHOES),

    // 상의
    JACKET(MainCategory.TOP),
    SWEATSHIRT(MainCategory.TOP),
    T_SHIRT(MainCategory.TOP),

    // 하의
    DENIM_PANTS(MainCategory.BOTTOM),
    SKIRT(MainCategory.BOTTOM),
    SHORT_PANTS(MainCategory.BOTTOM),

    // 악세사리
    NECKLACE(MainCategory.ACCESSORY),
    RING(MainCategory.ACCESSORY),
    GLASSES(MainCategory.ACCESSORY);

    private final MainCategory mainCategory;

    SubCategory(MainCategory mainCategory) {
        this.mainCategory = mainCategory;
    }

    public boolean belongsTo(MainCategory mainCategory) {
        return this.mainCategory == mainCategory;
    }
}
