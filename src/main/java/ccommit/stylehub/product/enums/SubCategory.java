package ccommit.stylehub.product.enums;

import lombok.Getter;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * 상품 소분류를 정의한다.
 * 대분류별 3개씩 총 12개의 소분류로 구성된다.
 * </p>
 */

@Getter
public enum SubCategory {

    // 신발
    SNEAKERS,      // 스니커즈
    DRESS_SHOES,   // 구두
    RUNNING_SHOES, // 운동화

    // 상의
    JACKET,        // 자켓
    SWEATSHIRT,    // 맨투맨
    T_SHIRT,       // 티셔츠

    // 하의
    DENIM_PANTS,   // 데님팬츠
    SKIRT,         // 치마
    SHORT_PANTS,   // 숏팬츠

    // 악세사리
    NECKLACE,      // 목걸이
    RING,          // 반지
    GLASSES        // 안경
}