package ccommit.stylehub.product.repository;

import ccommit.stylehub.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 * @modified 2026/03/27 by WonJin - feat: findByIdWithStore fetch join, 커서 기반 목록 조회 쿼리 추가
 *
 * <p>
 * Product 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("SELECT p FROM Product p JOIN FETCH p.store WHERE p.productId = :productId")
    Optional<Product> findByIdWithStore(@Param("productId") Long productId);
}
