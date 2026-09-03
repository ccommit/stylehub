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
 * @modified 2026/05/03 by WonJin - perf: decreaseStockAtomic 추가 — SELECT FOR UPDATE 비관적 락 제거, 단일 atomic UPDATE 로 락 점유 시간 0 화
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
     * - DB 가 단일 UPDATE 를 atomic 으로 처리 → race condition 자체가 발생 불가능
     * - WHERE stock_quantity >= :qty 조건이 음수 재고 방지 (SOLD_OUT 케이스도 자연스럽게 처리)
     * - 락 점유 시간 = 0 → SELECT FOR UPDATE 시절의 락 대기가 사라짐
     *
     * @return 1 = 차감 성공, 0 = 옵션 없거나 재고 부족 (호출자가 구분 처리 필요)
     */
    @Modifying
    @Query("UPDATE ProductOption po SET po.stockQuantity = po.stockQuantity - :qty " +
           "WHERE po.productOptionId = :optionId AND po.stockQuantity >= :qty")
    int decreaseStockAtomic(@Param("optionId") Long optionId, @Param("qty") int qty);

}
