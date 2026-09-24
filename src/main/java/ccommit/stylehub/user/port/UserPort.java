package ccommit.stylehub.user.port;

import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 * @modified 2026/04/22 by WonJin - refactor: 검증 전용 메서드 validateApprovedStoreOwner 분리 (의도 명확화)
 * @modified 2026/09/15 by WonJin - feat: 조회 없이 참조만 얻는 getUserReference 추가
 * @modified 2026/09/17 by WonJin - feat: 주문 포인트 차감·사용 이력 기록·취소 복구 추가 (order 도메인이 포인트를 이 포트로만 변경)
 *
 * <p>
 * User 도메인이 외부에 제공하는 포트 인터페이스이다.
 * 배송지 조회, 유저 조회, 스토어 소유권 검증/조회를 제공한다.
 * 검증만 필요한 경우 validateApprovedStoreOwner, 소유자 User 객체가 필요한 경우 findApprovedStoreByOwner를 사용한다.
 * </p>
 */
public interface UserPort {

    Address findAddressByOwner(Long userId, Long addressId);

    User findUserById(Long userId);

    // 조회 쿼리 없이 연관관계 설정용 참조만 얻는다. 존재가 이미 보장된 사용자(세션 사용자 등)에만 사용한다.
    User getUserReference(Long userId);

    void validateApprovedStoreOwner(Long userId, Long storeId);

    User findApprovedStoreByOwner(Long userId, Long storeId);

    // 포인트 사용 1단계 — 조건부 원자 UPDATE 로 차감하고 부족하면 INSUFFICIENT_POINT.
    // 사용자 행 배타 락을 커밋까지 쥐므로 주문 INSERT 보다 먼저 호출해야 한다(OrderService.placeOrder).
    void deductPoint(Long userId, int amount);

    // 포인트 사용 2단계 — 사용 이력(USE, 음수, 차감 후 잔액)을 남긴다. 같은 트랜잭션에서 deductPoint 를 먼저 호출해야 잔여 포인트가 맞다.
    void recordPointUse(Long userId, Long orderId, int amount);

    // 사용 포인트를 원자 UPDATE 로 되돌리고 취소 이력(USE, 양수)을 남긴다. 중복 호출은 막지 않으므로 호출자가 1회만 부르게 보장해야 한다.
    void restoreUsedPoint(Long userId, Long orderId, int amount);
}
