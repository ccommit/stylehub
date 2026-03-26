package ccommit.stylehub.user.repository;

import ccommit.stylehub.user.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * Address 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface AddressRepository extends JpaRepository<Address, Long> {

    @Query("SELECT a FROM Address a JOIN FETCH a.user WHERE a.addressId = :addressId")
    Optional<Address> findByIdWithUser(@Param("addressId") Long addressId);
}
