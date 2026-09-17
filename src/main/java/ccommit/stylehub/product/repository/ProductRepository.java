package ccommit.stylehub.product.repository;

import ccommit.stylehub.product.entity.Product;
import ccommit.stylehub.user.enums.StoreStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 * @modified 2026/03/27 by WonJin - feat: findByIdWithStore fetch join, 커서 기반 목록 조회 쿼리 추가
 * @modified 2026/04/01 by WonJin - feat: findByIdWithStoreAndOptions 옵션 포함 조회 추가
 * @modified 2026/09/17 by WonJin - fix: 상세 조회에 스토어 상태 조건 추가 (정지·미승인 스토어 상품 노출 차단)
 *
 * <p>
 * Product 엔티티의 기본 CRUD와 정적 조회를 담당한다.
 * 동적 조회는 ProductQueryRepository에서 QueryDSL로 처리한다.
 * </p>
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    // 스토어 상태를 파라미터로 받아 호출부에서 "어떤 상태의 상품만 보이는지" 가 드러나게 한다.
    @Query("SELECT DISTINCT p FROM Product p " +
            "JOIN FETCH p.user u " +
            "LEFT JOIN FETCH p.options " +
            "WHERE p.productId = :productId AND u.storeStatus = :storeStatus")
    Optional<Product> findByIdWithUserAndOptions(@Param("productId") Long productId,
                                                 @Param("storeStatus") StoreStatus storeStatus);
}
