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
 *
 * <p>
 * 사용자의 포인트 적립/사용 이력을 기록한다.
 * balanceSnapshot으로 거래 시점의 잔액을 보존한다.
 * </p>
 */

@Entity
@Table(name = "point_histories")
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
