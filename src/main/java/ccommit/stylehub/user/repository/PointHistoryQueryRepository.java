package ccommit.stylehub.user.repository;

import ccommit.stylehub.user.dto.response.PointHistoryResponse;
import ccommit.stylehub.user.entity.QPointHistory;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author WonJin Bae
 * @created 2026/09/17
 *
 * <p>
 * QueryDSL 기반 내 포인트 이력 커서 조회를 담당한다.
 * 엔티티 대신 응답 DTO 로 직접 투영해 사용자·주문 연관 엔티티를 로딩하지 않는다.
 * </p>
 */
@Repository
@RequiredArgsConstructor
public class PointHistoryQueryRepository {

    private final JPAQueryFactory queryFactory;

    // (user_id, point_id) 인덱스로 등치 + 범위·정렬을 인덱스 순서대로 읽는 것을 전제한다.
    // cursor 는 이전 페이지의 마지막 pointId(첫 페이지는 null), size 는 hasNext 판단을 위해 호출자가 pageSize + 1 을 넘긴다.
    public List<PointHistoryResponse> findMyHistoriesWithCursor(Long userId, Long cursor, int size) {
        QPointHistory pointHistory = QPointHistory.pointHistory;

        BooleanBuilder builder = new BooleanBuilder(pointHistory.user.userId.eq(userId));
        if (cursor != null) {
            builder.and(pointHistory.pointId.lt(cursor));
        }

        return queryFactory
                .select(Projections.constructor(PointHistoryResponse.class,
                        pointHistory.pointId,
                        pointHistory.pointType,
                        pointHistory.amount,
                        pointHistory.balanceSnapshot,
                        pointHistory.order.orderId,
                        pointHistory.createdAt))
                .from(pointHistory)
                .where(builder)
                .orderBy(pointHistory.pointId.desc())
                .limit(size)
                .fetch();
    }
}
