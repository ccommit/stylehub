package ccommit.stylehub.order.repository;

import ccommit.stylehub.order.dto.response.OrderTotalAmountDto;
import ccommit.stylehub.order.entity.OrderDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 * @modified 2026/04/08 by WonJin - refactor: OrderItemRepository → OrderDetailRepository 변경
 *
 * <p>
 * OrderDetail 엔티티의 데이터 접근을 담당한다.
 * </p>
 */
public interface OrderDetailRepository extends JpaRepository<OrderDetail, Long> {

    List<OrderDetail> findByOrderOrderId(Long orderId);

    @Query("SELECT od FROM OrderDetail od " +
            "JOIN FETCH od.productOption po " +
            "JOIN FETCH po.product p " +
            "JOIN FETCH p.store " +
            "WHERE od.order.orderId = :orderId")
    List<OrderDetail> findByOrderIdWithDetails(@Param("orderId") Long orderId);

    @Query("SELECT new ccommit.stylehub.order.dto.response.OrderTotalAmountDto(" +
            "od.order.orderId, COALESCE(SUM(od.quantity * od.unitPrice), 0)) " +
            "FROM OrderDetail od WHERE od.order.orderId IN :orderIds GROUP BY od.order.orderId")
    List<OrderTotalAmountDto> calculateTotalAmounts(@Param("orderIds") List<Long> orderIds);
}
