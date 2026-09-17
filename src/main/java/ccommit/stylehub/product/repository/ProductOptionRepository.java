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
 * @modified 2026/09/17 by WonJin - fix: increaseStockAtomic 추가 — 재고 복구가 먼저 읽어 둔 엔티티 값으로 덮어써 동시 차감을 잃던 문제 해결
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

    // 비관적 락 대신 단일 UPDATE로 차감해 Lost Update와 음수 재고를 막는다. 행 락은 그대로이고 SELECT 왕복·락 점유 구간만 줄어든다.
    @Modifying
    @Query("UPDATE ProductOption po SET po.stockQuantity = po.stockQuantity - :qty " +
           "WHERE po.productOptionId = :optionId AND po.stockQuantity >= :qty")
    int decreaseStockAtomic(@Param("optionId") Long optionId, @Param("qty") int qty);

    // 취소 시 먼저 올라간 옵션 엔티티는 락 조회로도 새로 읽히지 않아 동시 차감을 덮어쓰므로, 읽은 시점과 무관한 DB 현재 값에 더한다.
    @Modifying
    @Query("UPDATE ProductOption po SET po.stockQuantity = po.stockQuantity + :qty WHERE po.productOptionId = :optionId")
    int increaseStockAtomic(@Param("optionId") Long optionId, @Param("qty") int qty);

}
