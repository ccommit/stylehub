package ccommit.stylehub.user.enums;

import lombok.Getter;

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
