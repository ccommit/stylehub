package ccommit.stylehub.user.entity;

import ccommit.stylehub.common.entity.BaseEntity;
import ccommit.stylehub.common.exception.BusinessException;
import ccommit.stylehub.common.exception.ErrorCode;
import ccommit.stylehub.user.enums.OAuthProvider;
import ccommit.stylehub.user.enums.StoreStatus;
import ccommit.stylehub.user.enums.UserGrade;
import ccommit.stylehub.user.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * @author WonJin Bae
 * @created 2026/03/21 08:17
 * @modified 2026/03/14 19:00 by WonJin - refactor: 모든 엔티티 클래스의 JPA 와일드카드 import를 명시적 import로 교체
 * @modified 2026/03/16 18:16 by WonJin - feat: 회원 API 개발 (회원가입, 로그인, 구글 OAuth, 포인트 지급)
 * @modified 2026/03/21 08:17 by WonJin - refactor: bwj 패키지명 ccommit으로 변경
 * @modified 2026/03/25 by WonJin - feat: STORE 역할 회원 생성 팩토리 메서드 추가
 *
 * <p>
 * 회원 정보와 포인트, 등급, OAuth 연동을 관리하는 핵심 엔티티이다.
 * 일반 가입과 OAuth 가입을 정적 팩토리 메서드로 분리한다.
 * </p>
 */

@Entity
@Table(name = "users")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 20, unique = true)
    private String name;

    @Column(name = "provider_user_id", length = 100)
    private String providerUserId;

    @Enumerated(EnumType.STRING)
    private OAuthProvider provider;

    @Column(nullable = false, length = 100, unique = true)
    private String email;

    @Column(length = 400)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserRole role = UserRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserGrade grade = UserGrade.BRONZE;

    @Column(name = "total_spent", nullable = false)
    @Builder.Default
    private Long totalSpent = 0L;

    @Column(name = "point_balance", nullable = false)
    @Builder.Default
    private Integer pointBalance = 0;

    @Column(name = "last_login_date")
    private LocalDate lastLoginDate;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    // Store 필드 (STORE 역할 사용자만 사용, 나머지는 null)
    @Column(name = "store_name", length = 20)
    private String storeName;

    @Column(name = "store_description", length = 400)
    private String storeDescription;

    @Column(name = "store_like_count")
    @Builder.Default
    private Integer storeLikeCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "store_status")
    private StoreStatus storeStatus;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "store_deleted_at")
    private LocalDateTime storeDeletedAt;

    public void addPoint(int amount) {
        this.pointBalance += amount;
    }

    public void updateLastLoginDate(LocalDate today) {
        this.lastLoginDate = today;
    }

    public void registerStore(String storeName, String storeDescription) {
        this.storeName = storeName;
        this.storeDescription = storeDescription;
        this.storeStatus = StoreStatus.PENDING;
    }

    public void approveStore() {
        validateStoreStatus(StoreStatus.PENDING);
        this.storeStatus = StoreStatus.APPROVED;
        this.approvedAt = LocalDateTime.now();
    }

    public void rejectStore() {
        validateStoreStatus(StoreStatus.PENDING);
        this.storeStatus = StoreStatus.REJECTED;
    }

    public void suspendStore() {
        validateStoreStatus(StoreStatus.APPROVED);
        this.storeStatus = StoreStatus.SUSPENDED;
    }

    private void validateStoreStatus(StoreStatus expected) {
        if (this.storeStatus != expected) {
            throw new BusinessException(ErrorCode.INVALID_STORE_STATUS);
        }
    }

    public static User create(String name, String email, String hashedPassword, LocalDate birthDate, UserRole role) {
        return User.builder()
                .name(name)
                .email(email)
                .password(hashedPassword)
                .birthDate(birthDate)
                .role(role)
                .build();
    }

    public static User createOAuth(String name, String email, OAuthProvider provider, String providerUserId) {
        return User.builder()
                .name(name)
                .email(email)
                .provider(provider)
                .providerUserId(providerUserId)
                .build();
    }
}
