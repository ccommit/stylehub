package ccommit.stylehub.product.repository;

import ccommit.stylehub.product.entity.ProductOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * ProductOption 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    Optional<ProductOption> findByProductOptionIdAndProductProductId(Long optionId, Long productId);
}
