package ccommit.stylehub.order.repository;

import ccommit.stylehub.order.dto.response.OrderTotalAmountDto;
import ccommit.stylehub.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * OrderItem 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderOrderId(Long orderId);

    @Query("SELECT oi FROM OrderItem oi " +
            "JOIN FETCH oi.productOption po " +
            "JOIN FETCH po.product p " +
            "JOIN FETCH p.store " +
            "WHERE oi.order.orderId = :orderId")
    List<OrderItem> findByOrderIdWithDetails(@Param("orderId") Long orderId);

    @Query("SELECT new ccommit.stylehub.order.dto.response.OrderTotalAmountDto(" +
            "oi.order.orderId, COALESCE(SUM(oi.quantity * oi.unitPrice), 0)) " +
            "FROM OrderItem oi WHERE oi.order.orderId IN :orderIds GROUP BY oi.order.orderId")
    List<OrderTotalAmountDto> calculateTotalAmounts(@Param("orderIds") List<Long> orderIds);
}
