package ccommit.stylehub.store.repository;

import ccommit.stylehub.store.entity.Store;
import ccommit.stylehub.store.enums.StoreStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * Store 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface StoreRepository extends JpaRepository<Store, Long> {

    boolean existsByUserUserId(Long userId);

    Optional<Store> findByUserUserId(Long userId);

    List<Store> findByStatus(StoreStatus status);
}
