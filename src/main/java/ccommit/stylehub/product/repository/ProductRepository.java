package ccommit.stylehub.product.repository;

import ccommit.stylehub.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 *
 * <p>
 * Product 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
}
