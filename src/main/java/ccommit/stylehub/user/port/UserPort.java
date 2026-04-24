package ccommit.stylehub.user.port;

import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 * @modified 2026/04/22 by WonJin - refactor: 검증 전용 메서드 validateApprovedStoreOwner 분리 (의도 명확화)
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

    void validateApprovedStoreOwner(Long userId, Long storeId);

    User findApprovedStoreByOwner(Long userId, Long storeId);
}
