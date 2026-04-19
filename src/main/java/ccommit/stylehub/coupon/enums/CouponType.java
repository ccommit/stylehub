package ccommit.stylehub.coupon.enums;

/**
 * @author WonJin Bae
 * @created 2026/04/16
 *
 * <p>
 * 쿠폰 이벤트의 발행 주체 타입을 정의한다.
 * PLATFORM: 관리자가 발행한 플랫폼 전역 쿠폰 (store = null)
 * STORE: 스토어가 발행한 개별 스토어 쿠폰 (store != null)
 * </p>
 */
public enum CouponType {

    PLATFORM,   // 관리자 발행 (플랫폼 전역)
    STORE       // 스토어 발행 (개별 스토어)
}
