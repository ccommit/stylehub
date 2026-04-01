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
 * @modified 2026/04/01 by WonJin - feat: findByIdWithStoreAndOptions 옵션 포함 조회 추가
 *
 * <p>
 * Product 엔티티의 기본 CRUD와 정적 조회를 담당한다.
 * 동적 조회는 ProductQueryRepository에서 QueryDSL로 처리한다.
 * </p>
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("SELECT DISTINCT p FROM Product p " +
            "JOIN FETCH p.store " +
            "LEFT JOIN FETCH p.options " +
            "WHERE p.productId = :productId")
    Optional<Product> findByIdWithStoreAndOptions(@Param("productId") Long productId);
}
