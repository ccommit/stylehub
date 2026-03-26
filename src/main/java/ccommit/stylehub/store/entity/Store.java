package ccommit.stylehub.store.entity;

import ccommit.stylehub.common.entity.BaseEntity;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.store.enums.StoreStatus;
import ccommit.stylehub.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 19:00 by WonJin - refactor: 모든 엔티티 클래스의 JPA 와일드카드 import를 명시적 import로 교체
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/03/25 by WonJin - feat: 입점 승인/거절/정지 상태 변경 메서드 추가
 *
 * <p>
 * 입점 매장(스토어) 정보를 관리한다.
 * User와 1:1 관계로 한 사용자당 하나의 스토어만 운영 가능하다.
 * </p>
 */

@Entity
@Table(name = "stores")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Long storeId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(nullable = false, length = 400)
    private String description;

    @Column(name = "like_count", nullable = false)
    @Builder.Default
    private Integer likeCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private StoreStatus status = StoreStatus.PENDING;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public static Store create(User user, String name, String description) {
        return Store.builder()
                .user(user)
                .name(name)
                .description(description)
                .build();
    }

    public void approve() {
        validateStatus(StoreStatus.PENDING);
        this.status = StoreStatus.APPROVED;
        this.approvedAt = LocalDateTime.now();
    }

    public void reject() {
        validateStatus(StoreStatus.PENDING);
        this.status = StoreStatus.REJECTED;
    }

    public void suspend() {
        validateStatus(StoreStatus.APPROVED);
        this.status = StoreStatus.SUSPENDED;
    }

    private void validateStatus(StoreStatus expected) {
        if (this.status != expected) {
            throw new BusinessException(ErrorCode.INVALID_STORE_STATUS);
        }
    }
}
