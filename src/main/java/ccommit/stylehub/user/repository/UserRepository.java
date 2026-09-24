package ccommit.stylehub.user.repository;

import ccommit.stylehub.user.entity.Address;
import ccommit.stylehub.user.entity.User;
import ccommit.stylehub.user.enums.StoreStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/09/17 by WonJin - feat: 배송지 변경 직렬화용 사용자 행 비관적 락 조회(findByIdWithLock) 추가
 * @modified 2026/09/17 by WonJin - feat: 포인트 조건부 차감·복구·로그인 적립 원자 UPDATE 와 잔액 스칼라 조회 추가 (엔티티 값에 더해 저장하던 lost update 제거)
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

    // ========================
    // 포인트 (모든 변경은 원자 UPDATE)
    // ========================
    //
    // 엔티티를 읽어 메모리에서 더한 뒤 저장하면, 읽은 뒤 커밋된 다른 트랜잭션의 차감·복구를 모르는 값으로 전체 컬럼을 덮어쓴다.
    // DB 의 현재 값에 더하고 빼는 UPDATE 는 읽은 시점과 무관하므로 이 덮어쓰기가 생기지 않는다.
    // 벌크 UPDATE 는 영속성 컨텍스트의 User 엔티티를 갱신하지 않는다. 변경 후 잔액은 반드시 findPointBalance 로 다시 읽는다.
    // clearAutomatically 를 쓰지 않는 이유: 주문 생성처럼 같은 트랜잭션에서 아직 반영 전인 다른 엔티티 변경이 함께 사라질 수 있다.

    // WHERE point_balance >= :amount 가 잔액 확인과 차감을 한 문장으로 묶는다. 동시 주문은 행 배타 락으로 줄을 서 잔액이 음수가 되지 않는다.
    // 반환값 1 = 차감 성공, 0 = 잔액 부족 또는 사용자 없음
    @Modifying
    @Query("UPDATE User u SET u.pointBalance = u.pointBalance - :amount " +
           "WHERE u.userId = :userId AND u.pointBalance >= :amount")
    int deductPointIfEnough(@Param("userId") Long userId, @Param("amount") int amount);

    // 주문 취소의 사용 포인트 복구가 쓴다. 반환값 1 = 성공, 0 = 사용자 없음
    @Modifying
    @Query("UPDATE User u SET u.pointBalance = u.pointBalance + :amount WHERE u.userId = :userId")
    int addPoint(@Param("userId") Long userId, @Param("amount") int amount);

    // 첫 로그인(last_login_date IS NULL)에만 적립한다. 동시 호출이어도 먼저 커밋한 한 건 뒤에는 조건이 거짓이 되어 0 을 돌려준다.
    @Modifying
    @Query("UPDATE User u SET u.pointBalance = u.pointBalance + :amount, u.lastLoginDate = :today " +
           "WHERE u.userId = :userId AND u.lastLoginDate IS NULL")
    int rewardFirstLoginPoint(@Param("userId") Long userId, @Param("amount") int amount,
                              @Param("today") LocalDate today);

    // 하루 1회 적립. 조건을 "오늘과 다름"이 아니라 "오늘보다 이전"으로 둬, 시계 차이로 다음 날짜가 먼저 기록돼도 되돌리거나 다시 적립하지 않는다.
    @Modifying
    @Query("UPDATE User u SET u.pointBalance = u.pointBalance + :amount, u.lastLoginDate = :today " +
           "WHERE u.userId = :userId AND u.lastLoginDate < :today")
    int rewardDailyLoginPoint(@Param("userId") Long userId, @Param("amount") int amount,
                              @Param("today") LocalDate today);

    // 현재 잔액을 DB 에서 바로 읽는다. 스칼라 조회라 영속성 컨텍스트에 남은 오래된 User 엔티티 값을 거치지 않는다. 사용자가 없으면 null.
    @Query("SELECT u.pointBalance FROM User u WHERE u.userId = :userId")
    Integer findPointBalance(@Param("userId") Long userId);

    // 로그인 적립 여부 판단에 필요한 값만 DTO 로 읽는다. 판단은 이후 조건부 UPDATE 가 다시 확정하므로 락을 걸지 않는다.
    @Query("SELECT new ccommit.stylehub.user.repository.LoginPointState(u.role, u.lastLoginDate) " +
           "FROM User u WHERE u.userId = :userId")
    Optional<LoginPointState> findLoginPointState(@Param("userId") Long userId);
}
