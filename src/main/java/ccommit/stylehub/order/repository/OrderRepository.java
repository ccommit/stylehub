package ccommit.stylehub.order.repository;

import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/09/17 by WonJin - fix: 보정 대상 조회에 정렬 추가 (배치 제한 안에서 오래된 주문부터 처리)
 *
 * <p>
 * Order 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderId = :orderId")
    Optional<Order> findByIdWithLock(@Param("orderId") Long orderId);

    // 결론을 미룬 주문은 PENDING 으로 남아 다시 조회되므로, 마지막으로 본 ID 다음부터 읽어 같은 주문을 반복해 꺼내지 않는다.
    @Query("SELECT o.orderId FROM Order o WHERE o.orderStatus = :status AND o.createdAt < :expiredTime " +
           "AND o.orderId > :lastOrderId ORDER BY o.orderId")
    List<Long> findExpiredOrderIds(@Param("status") OrderStatus status,
                                   @Param("expiredTime") LocalDateTime expiredTime,
                                   @Param("lastOrderId") Long lastOrderId,
                                   Pageable pageable);
}
