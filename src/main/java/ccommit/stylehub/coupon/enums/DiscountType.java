package ccommit.stylehub.coupon.enums;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/04/09 by WonJin - refactor: 전략 패턴 적용, 할인 계산 로직을 enum에 캡슐화
 *
 * <p>
 * 쿠폰의 할인 방식을 정의하고, 각 타입별 할인 금액 계산 로직을 캡슐화한다.
 * 새로운 할인 유형 추가 시 enum 상수만 추가하면 된다.
 * </p>
 */
public enum DiscountType {

    FIXED {
        @Override
        public int calculate(int orderAmount, int discountValue) {
            return Math.min(discountValue, orderAmount);
        }
    },

    RATE {
        @Override
        public int calculate(int orderAmount, int discountValue) {
            return orderAmount * discountValue / 100;
        }
    };

    public abstract int calculate(int orderAmount, int discountValue);
}
