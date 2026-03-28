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
 *
 * <p>
 * Order 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderId = :orderId")
    Optional<Order> findByIdWithLock(@Param("orderId") Long orderId);

    @Query("SELECT o FROM Order o WHERE o.orderStatus = :status AND o.createdAt < :expiredTime")
    List<Order> findExpiredOrders(@Param("status") OrderStatus status,
                                 @Param("expiredTime") LocalDateTime expiredTime,
                                 Pageable pageable);
}
