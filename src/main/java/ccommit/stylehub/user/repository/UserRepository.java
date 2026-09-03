package ccommit.stylehub.user.repository;

import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.StoreStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 *
 * <p>
 * User 엔티티의 데이터 접근을 담당한다.
 * </p>
 */

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    boolean existsByName(String name);

    Optional<User> findByEmail(String email);

    boolean existsByStoreNameNotNull(Long userId);

    List<User> findByStoreStatus(StoreStatus status);

    List<User> findByStoreStatusNotNull();

    @Query("SELECT a FROM Address a JOIN FETCH a.user WHERE a.addressId = :addressId")
    Optional<Address> findAddressByIdWithUser(@Param("addressId") Long addressId);
}
