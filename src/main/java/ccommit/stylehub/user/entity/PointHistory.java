package ccommit.stylehub.user.entity;

import ccommit.stylehub.order.entity.Order;
import ccommit.stylehub.user.enums.PointType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 19:00 by WonJin - refactor: 모든 엔티티 클래스의 JPA 와일드카드 import를 명시적 import로 교체
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/09/17 by WonJin - feat: 금액 부호 규칙 문서화, 주문 포인트 사용·사용 취소 팩토리 추가, 내 포인트 이력 커서 조회용 (user_id, point_id) 인덱스 선언
 *
 * <p>
 * 사용자의 포인트 적립/사용 이력을 기록한다.
 * balanceSnapshot으로 거래 시점의 잔액을 보존한다.
 * </p>
 *
 * <p><b>amount 부호 규칙</b> 잔액에 더해진 값을 그대로 기록한다. 적립(WELCOME, DAILY_LOGIN, EARN)과 사용 취소는 양수,
 * 사용(USE)은 음수다. 따라서 한 사용자의 amount 합계는 이력 도입 이후의 잔액 변화량과 같다.
 *
 * <p><b>사용 취소를 USE + 양수로 기록하는 이유</b> 주문 취소로 포인트를 되돌리는 것은 새 적립이 아니라 그 주문 사용의 역분개다.
 * 별도 유형(예: 사용 취소)을 두는 편이 조회에는 명확하지만 point_type 은 DB 에 저장되는 enum 이고, 운영 컬럼이 MySQL ENUM 일 수 있으며
 * 운영은 ddl-auto=validate 라 상수를 추가하면 스키마 변경 없이는 저장이 실패할 수 있다. 그래서 같은 주문의 USE 를 반대 부호로 남기고,
 * 사용과 사용 취소는 amount 부호로 구분한다.
 */

@Entity
@Table(name = "point_histories", indexes = {
        // PointHistoryQueryRepository.findMyHistoriesWithCursor(user_id = ? AND point_id < ? ORDER BY point_id DESC)가 전제하는 인덱스다.
        // 운영 DB 는 ddl-auto=validate 라 이 선언으로 만들어지지 않는다. 운영 반영 DDL: scripts/db/create-point-history-cursor-index.sql
        @Index(name = "idx_point_histories_user_point_id", columnList = "user_id, point_id")
})
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "point_id")
    private Long pointId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "point_type", nullable = false)
    private PointType pointType;

    @Column(nullable = false)
    private Integer amount;

    @Column(name = "balance_snapshot", nullable = false)
    private Integer balanceSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 주문 없는 포인트 (WELCOME, DAILY_LOGIN)
    public static PointHistory create(User user, PointType pointType,
                                      Integer amount, Integer balanceSnapshot) {
        return PointHistory.builder()
                .user(user)
                .pointType(pointType)
                .amount(amount)
                .balanceSnapshot(balanceSnapshot)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // 주문 포인트 사용 — 잔액에서 빠진 값이므로 음수로 기록한다.
    public static PointHistory ofUse(User user, Order order, int usedAmount, int balanceSnapshot) {
        return createWithOrder(user, order, PointType.USE, -usedAmount, balanceSnapshot);
    }

    // 주문 취소로 사용 포인트를 되돌린다 — 같은 주문의 USE 를 양수로 역분개한다(유형을 늘리지 않는 이유는 클래스 설명 참고).
    public static PointHistory ofUseCancel(User user, Order order, int restoredAmount, int balanceSnapshot) {
        return createWithOrder(user, order, PointType.USE, restoredAmount, balanceSnapshot);
    }

    // 주문 있는 포인트 (EARN, USE)
    public static PointHistory createWithOrder(User user, Order order, PointType pointType,
                                               Integer amount, Integer balanceSnapshot) {
        return PointHistory.builder()
                .user(user)
                .order(order)
                .pointType(pointType)
                .amount(amount)
                .balanceSnapshot(balanceSnapshot)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
