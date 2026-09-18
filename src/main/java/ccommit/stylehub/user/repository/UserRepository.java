package ccommit.stylehub.user.repository;

import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.StoreStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/09/17 by WonJin - feat: 배송지 변경 직렬화용 사용자 행 비관적 락 조회(findByIdWithLock) 추가
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

    // 배송지 변경을 사용자 단위로 직렬화한다. 첫 등록 시에는 잠글 배송지 행이 없어 addresses 대신 users 행을 잠근다.
    // 없는 행을 FOR UPDATE로 읽으면 InnoDB(REPEATABLE READ)는 서로 막지 않는 갭 락만 걸어 두 요청이 함께 0개를 읽는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.userId = :userId")
    Optional<User> findByIdWithLock(@Param("userId") Long userId);
}
