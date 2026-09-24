package ccommit.stylehub.user.repository;

import ccommit.stylehub.user.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * Address 엔티티의 데이터 접근을 담당한다.
 * 사용자당 배송지가 최대 5개로 제한되므로 목록은 페이징 없이 한 번에 읽는다.
 * </p>
 */
public interface AddressRepository extends JpaRepository<Address, Long> {

    // 최근 등록순을 created_at 대신 IDENTITY로 증가하는 address_id로 정렬해 같은 초에 등록돼도 순서가 흔들리지 않는다.
    // 기본 배송지 승계 기준(addressId 최대)과도 일치한다.
    @Query("SELECT a FROM Address a " +
            "WHERE a.user.userId = :userId " +
            "ORDER BY a.defaultAddress DESC, a.addressId DESC")
    List<Address> findAllByUserIdOrderByDefaultFirst(@Param("userId") Long userId);
}
