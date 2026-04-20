package ccommit.stylehub.user.port;

import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;

/**
 * @author WonJin Bae
 * @created 2026/04/20
 *
 * <p>
 * User 도메인이 외부에 제공하는 포트 인터페이스이다.
 * 배송지 조회, 유저 조회, 스토어 소유권 검증을 제공한다.
 * </p>
 */
public interface UserPort {

    Address findAddressByOwner(Long userId, Long addressId);

    User findUserById(Long userId);

    User findApprovedStoreByOwner(Long userId, Long storeId);
}
