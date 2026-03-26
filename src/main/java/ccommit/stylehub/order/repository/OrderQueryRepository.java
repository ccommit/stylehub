package ccommit.stylehub.order.repository;

import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.order.entity.QOrder;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/03/27
 *
 * <p>
 * QueryDSL 기반 주문 내역 동적 조회를 담당한다.
 * 커서 기반 페이징으로 본인 주문 내역을 조회한다.
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class OrderQueryRepository {

    private final JPAQueryFactory queryFactory;

    public List<Order> findMyOrdersWithCursor(Long userId, Long cursor, int size) {
        QOrder order = QOrder.order;

        BooleanBuilder builder = new BooleanBuilder();
        builder.and(order.user.userId.eq(userId));

        if (cursor != null) {
            builder.and(order.orderId.lt(cursor));
        }

        return queryFactory
                .selectFrom(order)
                .where(builder)
                .orderBy(order.orderId.desc())
                .limit(size)
                .fetch();
    }
}
