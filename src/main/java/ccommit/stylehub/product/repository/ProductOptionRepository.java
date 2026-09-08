package ccommit.stylehub.product.repository;

import ccommit.stylehub.product.entity.ProductOption;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/03/25
 * @modified 2026/03/27 by WonJin - feat: findByProductProductId 조회, 비관적 락 조회 메서드 추가
 * @modified 2026/05/03 by WonJin - perf: decreaseStockAtomic 추가 — SELECT FOR UPDATE 대신 단일 atomic UPDATE (쿼리 2번 → 1번, 락 획득 시점을 트랜잭션 뒤로 이동)
 *
 * <p>
 * ProductOption 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    Optional<ProductOption> findByProductOptionIdAndProductProductId(Long optionId, Long productId);

    List<ProductOption> findByProductProductId(Long productId);

    // 비관적 락(SELECT FOR UPDATE)으로 옵션, 상품, 스토어를 함께 조회한다. (updateStock — 재고 수동 변경 시 사용)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM ProductOption po JOIN FETCH po.product p JOIN FETCH p.user WHERE po.productOptionId = :optionId")
    Optional<ProductOption> findByIdWithLock(@Param("optionId") Long optionId);

    /**
     * 재고를 단일 atomic UPDATE 로 차감한다. 비관적 락 대신 사용.
     * - DB 가 단일 UPDATE 를 원자적으로 처리 → 조회와 차감 사이의 Lost Update 가 발생하지 않음
     * - WHERE stock_quantity >= :qty 조건이 음수 재고 방지 (SOLD_OUT 케이스도 자연스럽게 처리)
     * - UPDATE 도 행에 배타 락을 걸고 커밋까지 유지한다. 락이 없어진 것이 아니라 SELECT 왕복이
     *   사라지고 락을 쥔 채 지나는 구간이 짧아진 것이다. 동일 행 경합 한계는 비관적 락과 같다.
     *
     * @return 1 = 차감 성공, 0 = 옵션 없거나 재고 부족 (호출자가 구분 처리 필요)
     */
    @Modifying
    @Query("UPDATE ProductOption po SET po.stockQuantity = po.stockQuantity - :qty " +
           "WHERE po.productOptionId = :optionId AND po.stockQuantity >= :qty")
    int decreaseStockAtomic(@Param("optionId") Long optionId, @Param("qty") int qty);

}
