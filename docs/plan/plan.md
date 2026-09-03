# 엔티티 리팩토링 프롬프트 (Claude Code용)

아래 User 엔티티 코드 스타일과 패턴을 기준으로 나머지 모든 엔티티를 동일한 방식으로 수정해줘.

---

## 적용할 패턴

### 1. `@SuperBuilder` + `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
- 외부에서 `new` 생성자 직접 호출 불가
- Builder를 통한 생성만 허용

### 2. `@Builder.Default`로 기본값 처리
- `@PrePersist`, `@ColumnDefault` 제거
- 기본값이 있는 필드는 `@Builder.Default`로 대체
```java
@Builder.Default
private Boolean active = true;
```

### 3. 정적 팩토리 메서드 추가
- 각 엔티티의 생성 시나리오에 맞게 `create()` 메서드 작성
- 필수 필드는 파라미터로 강제
- 생성 목적이 다른 경우 메서드명으로 구분 (ex. `create()`, `createOAuth()`)
- 내부적으로는 `builder()` 사용

### 4. 네이밍 규칙
- Boolean 필드는 `is` 접두사 제거
    - `isActive` → `active`
    - `isDefault` → `defaultAddress`
- `@Column(name = "is_active")` 으로 DB 컬럼명은 유지

### 5. 타입 정확성
- 날짜만 필요한 필드는 `LocalDateTime` → `LocalDate`
    - ex) `birthDate`, `lastLoginDate`

### 6. unique 제약 조건
- DDL에 `UNIQUE KEY`가 있는 컬럼은 `@Column(unique = true)` 추가

---

## 기준 코드 (User.java)

```java
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

    @Column(nullable = false, length = 20)
    private String name;

    @Column(name = "provider_user_id", length = 100)
    private String providerUserId;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    @Column(nullable = false, length = 100, unique = true)
    private String email;

    @Column(length = 400)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Grade grade = Grade.BRONZE;

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

    public static User create(String name, String email, String password) {
        return User.builder()
                .name(name)
                .email(email)
                .password(password)
                .build();
    }

    public static User createOAuth(String name, String email, Provider provider, String providerUserId) {
        return User.builder()
                .name(name)
                .email(email)
                .provider(provider)
                .providerUserId(providerUserId)
                .build();
    }
}
```

---

## 수정 대상 엔티티

| 엔티티 | 주요 체크 포인트 |
|--------|----------------|
| `Address` | `isDefault` → `defaultAddress`, `deleted_at` 필드 유지 |
| `Store` | `deleted_at` 필드 유지, StoreStatus 기본값 PENDING |
| `Product` | `likeCount` 기본값 0 |
| `ProductOption` | `stockQuantity`, `additionalPrice` 기본값 0 |
| `Order` | `discountAmount`, `usedPoint`, `earnedPoint` 기본값 0 |
| `OrderItem` | 연관관계 매핑 유지 |
| `Payment` | PaymentStatus 기본값 READY |
| `CouponEvent` | `isActive` → `active`, `minOrderAmount` 기본값 0 |
| `UserCoupon` | CouponStatus 기본값 UNUSED |
| `PointHistory` | 생성만 있고 수정 없는 이력 테이블 |

---

## 주의사항

- `BaseEntity` 상속 구조 유지
- 연관관계 매핑(`@ManyToOne`, `@OneToMany` 등) 기존 설정 유지
- 각 엔티티의 생성 시나리오를 고려해서 `create()` 메서드 파라미터 결정
- `soft delete` 컬럼(`deleted_at`)이 있는 엔티티(`Address`, `Store`)는 `deletedAt` 필드 유지
- 수정 완료 후 누락된 항목 없는지 검토해줘