# 입점 신청 및 승인 API 구현 정리

## 1. 전체 아키텍처

### 왜 이렇게 설계했는가?

대용량 트래픽을 고려한 패션 이커머스 플랫폼에서, 입점 신청은 **회원가입과 동시에** 이루어지며, 승인/거절/정지는 **Admin만** 수행한다. 이 요구사항에 맞춰 다음 원칙을 적용했다.

- **DDD(도메인 주도 설계)**: Store 도메인이 User Repository를 직접 참조하지 않음
- **기존 API 재사용**: 스토어 회원가입 시 기존 `UserService.signUp()`을 그대로 호출하고, 입점 신청만 추가 호출하여 중복 코드 방지
- **엔티티 캡슐화**: 상태 변경 로직을 엔티티 내부에 배치하여 잘못된 상태 전이 방지
- **SRP(단일 책임 원칙)**: 컨트롤러/서비스를 역할별로 분리

### 패키지 구조

```
store/
├── entity/
│   └── Store.java                  — 엔티티 (상태 변경 도메인 로직 포함)
├── enums/
│   └── StoreStatus.java            — 입점 상태 (PENDING/APPROVED/REJECTED/SUSPENDED)
├── repository/
│   └── StoreRepository.java        — 데이터 접근
├── dto/
│   ├── request/
│   │   ├── StoreSignUpRequest.java  — 스토어 회원가입 + 입점 신청 요청
│   └── response/
│       ├── StoreResponse.java       — 스토어 정보 응답
│       └── StoreSignUpResponse.java — 스토어 회원가입 결과 응답
├── service/
│   ├── StoreService.java           — 스토어 생성/조회 (STORE 역할)
│   └── StoreAdminService.java      — 승인/거절/정지/목록 조회 (ADMIN 역할)
└── controller/
    ├── StoreController.java        — STORE 역할 API
    └── StoreAdminController.java   — ADMIN 역할 API
```

### API 설계

| Method | URL | 역할 | 설명 |
|--------|-----|------|------|
| POST | `/api/v1/users/sign-up/store` | 비인증 | 스토어 회원가입 + 입점 신청 (동시) |
| GET | `/api/v1/stores/my` | STORE | 내 스토어 조회 |
| GET | `/api/v1/admin/stores?status=PENDING` | ADMIN | 입점 신청 목록 조회 (상태별 필터링) |
| GET | `/api/v1/admin/stores/{storeId}` | ADMIN | 입점 신청 상세 조회 |
| PATCH | `/api/v1/admin/stores/{storeId}/approve` | ADMIN | 입점 승인 |
| PATCH | `/api/v1/admin/stores/{storeId}/reject` | ADMIN | 입점 거절 |
| PATCH | `/api/v1/admin/stores/{storeId}/suspend` | ADMIN | 운영 정지 |

---

## 2. Store 엔티티

```java
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
}
```

### 메서드별 역할

**`Store.create(User user, String name, String description)`**
- 정적 팩토리 메서드로 Store 생성
- 생성 시 status는 `PENDING`(기본값), likeCount는 `0`(기본값)
- 생성자 대신 팩토리 메서드를 사용하여 **객체 생성 의도를 명확히** 함

**`approve()`**
- PENDING → APPROVED 상태 전이
- `approvedAt`에 현재 시간 자동 설정
- **왜 엔티티 내부에?**: 상태 변경은 도메인 핵심 규칙이므로 엔티티가 스스로 관리해야 함. 서비스에서 `store.setStatus(APPROVED)`로 외부에서 변경하면 비즈니스 규칙이 흩어짐

**`reject()`**
- PENDING → REJECTED 상태 전이
- PENDING이 아닌 상태에서 호출하면 `INVALID_STORE_STATUS` 예외

**`suspend()`**
- APPROVED → SUSPENDED 상태 전이
- 승인된 스토어만 정지 가능. PENDING이나 REJECTED 상태에서는 정지 불가

**`validateStatus(StoreStatus expected)`**
- 현재 상태가 기대 상태와 다르면 `BusinessException` 던짐
- **잘못된 상태 전이를 엔티티 레벨에서 방지** (예: REJECTED → APPROVED 불가)
- 서비스가 아닌 엔티티에서 검증하는 이유: **상태 전이 규칙은 비즈니스 도메인의 핵심 규칙**이므로

### 상태 전이 다이어그램

```
PENDING ──approve()──→ APPROVED ──suspend()──→ SUSPENDED
   │
   └──reject()──→ REJECTED
```

- REJECTED에서는 어디로도 갈 수 없음 (최종 상태)
- SUSPENDED에서도 복구 경로 없음 (추후 필요하면 reactivate() 추가)

---

## 3. StoreStatus Enum

```java
@Getter
public enum StoreStatus {
    PENDING,    // 입점 심사 중 (신청 직후 초기 상태)
    APPROVED,   // 입점 승인 완료
    SUSPENDED,  // 스토어 운영 정지
    REJECTED    // 입점 거절
}
```

**왜 Enum으로?**: 상태가 고정된 값이고, 잘못된 상태값이 들어오는 것을 컴파일 타임에 방지

---

## 4. StoreRepository

```java
public interface StoreRepository extends JpaRepository<Store, Long> {
    boolean existsByUserUserId(Long userId);
    Optional<Store> findByUserUserId(Long userId);
    List<Store> findByStatus(StoreStatus status);
}
```

### 메서드별 역할

- **`existsByUserUserId()`**: 1인 1스토어 중복 검증용. `SELECT COUNT`로 실행되어 엔티티 로딩 없이 빠름
- **`findByUserUserId()`**: 내 스토어 조회. User의 userId로 Store를 찾음 (User → Store 방향 조회)
- **`findByStatus()`**: Admin이 상태별 스토어 목록을 필터링할 때 사용

**왜 이렇게?**: Spring Data JPA의 메서드 이름 기반 쿼리 생성을 활용. 별도 `@Query` 없이 깔끔하게 구현

---

## 5. 스토어 회원가입 흐름 (UserController → UserService + StoreService)

### 왜 Facade를 사용하지 않는가?

초기에는 Facade 패턴으로 User + Store 저장을 하나의 트랜잭션에 묶었으나, 다음 이유로 제거했다:

1. **중복 코드 발생**: Facade의 `signUpWithStore()`가 `UserService.signUp()`의 BCrypt 해싱 + 예외 처리 로직을 그대로 재구현
2. **단순함 우선**: 컨트롤러에서 기존 `userService.signUp()` + `storeService.saveStore()`를 순차 호출하면 기존 회원가입 로직을 100% 재사용

### 원자성 트레이드오프

User 저장 성공 → Store 저장 실패 시 User만 남는 가능성이 있지만:
- 방금 가입한 사용자이므로 1인 1스토어 중복 실패는 불가능
- DB 장애는 극히 드문 케이스
- 발생하더라도 STORE 역할 User가 스토어 없이 존재하는 것뿐이고, 재신청 로직 추가로 대응 가능

**단순함 vs 원자성 트레이드오프에서 단순함을 선택**한 것이 현재 단계에서 합리적이다.

### 컨트롤러 코드

```java
@PostMapping("/sign-up/store")
public ResponseEntity<StoreSignUpResponse> signUpWithStore(@Valid @RequestBody StoreSignUpRequest request) {
    User user = userService.signUp(request.name(), request.email(), request.password(), null, UserRole.STORE);
    Store store = storeService.saveStore(user, request.storeName(), request.storeDescription());
    return ResponseEntity.status(HttpStatus.CREATED).body(StoreSignUpResponse.from(user, store));
}
```

- `userService.signUp()` — 기존 회원가입 로직 그대로 재사용 (BCrypt 해싱, 검증, User 저장)
- `storeService.saveStore()` — 입점 신청만 담당 (중복 검증, Store 저장)
- birthDate는 `null` 전달 — 스토어 회원은 생일 불필요

---

## 6. StoreService

```java
@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreRepository storeRepository;

    public Store saveStore(User user, String storeName, String storeDescription) {
        if (storeRepository.existsByUserUserId(user.getUserId())) {
            throw new BusinessException(ErrorCode.STORE_ALREADY_EXISTS);
        }
        Store store = Store.create(user, storeName, storeDescription);
        return storeRepository.save(store);
    }

    @Transactional(readOnly = true)
    public StoreResponse getMyStore(Long userId) {
        Store store = storeRepository.findByUserUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        return StoreResponse.from(store);
    }
}
```

### 메서드별 역할

**`saveStore()`**
- 1인 1스토어 중복 검증 후 Store 생성 및 저장
- `Store` 엔티티를 반환 (DTO가 아닌 엔티티). 컨트롤러에서 응답 DTO 조합에 사용

**`getMyStore()`**
- STORE 역할 사용자가 자신의 스토어를 조회
- `@Transactional(readOnly = true)` — 읽기 전용 트랜잭션으로 DB 최적화 (flush 생략, 읽기 전용 커넥션 풀 사용 가능). 추후 CQRS 적용 시 조회 전용 DB로 라우팅 가능

### 왜 EntityManager.getReference()를 쓰지 않는가?

컨트롤러에서 `userService.signUp()`이 반환한 `User` 엔티티를 `storeService.saveStore()`에 직접 전달하므로 프록시가 불필요. `EntityManager` 의존을 제거하여 JPA 구현체 결합도를 낮춤.

---

## 7. StoreAdminService

```java
@Service
@RequiredArgsConstructor
public class StoreAdminService {

    private final StoreRepository storeRepository;
    private final TransactionTemplate transactionTemplate;

    @Transactional(readOnly = true)
    public List<StoreResponse> getStoresByStatus(StoreStatus status) { ... }

    @Transactional(readOnly = true)
    public StoreResponse getStore(Long storeId) { ... }

    public StoreResponse approve(Long storeId) {
        return changeStatus(storeId, Store::approve);
    }

    public StoreResponse reject(Long storeId) {
        return changeStatus(storeId, Store::reject);
    }

    public StoreResponse suspend(Long storeId) {
        return changeStatus(storeId, Store::suspend);
    }

    private StoreResponse changeStatus(Long storeId, Consumer<Store> action) {
        Store store = Objects.requireNonNull(
                transactionTemplate.execute(status -> {
                    Store target = findStoreById(storeId);
                    action.accept(target);
                    return target;
                })
        );
        return StoreResponse.from(store);
    }
}
```

### 메서드별 역할

**`getStoresByStatus(StoreStatus status)`**
- `status` 파라미터가 있으면 해당 상태만, 없으면 전체 조회
- `@Transactional(readOnly = true)` 적용

**`getStore(Long storeId)`**
- 개별 스토어 상세 조회 (Admin용)

**`approve()` / `reject()` / `suspend()`**
- 각각 `changeStatus()`에 `Store::approve` / `Store::reject` / `Store::suspend` 메서드 레퍼런스를 전달
- **왜 메서드 레퍼런스?**: 세 메서드의 구조가 동일(조회 → 상태 변경 → 반환)하므로, `Consumer<Store>`로 변경 동작만 주입하여 중복 제거 (DRY 원칙)

**`changeStatus(Long storeId, Consumer<Store> action)`**
- 공통 상태 변경 로직. `TransactionTemplate` 내에서:
  1. Store 조회 (영속 상태)
  2. action 실행 (상태 변경 → dirty checking으로 자동 UPDATE)
  3. 변경된 Store 반환
- **왜 TransactionTemplate?**: 조회한 엔티티가 영속성 컨텍스트에서 관리되어야 dirty checking이 동작함. 트랜잭션 없이 조회하면 detached 상태가 되어 상태 변경이 DB에 반영되지 않음

**`findStoreById(Long storeId)`**
- 공통 조회 + 예외 처리. `STORE_NOT_FOUND` 에러를 한 곳에서 관리

---

## 8. StoreController

```java
@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
@RequiredRole(UserRole.STORE)
public class StoreController {

    private final StoreService storeService;

    @GetMapping("/my")
    public ResponseEntity<StoreResponse> getMyStore(HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(storeService.getMyStore(userId));
    }
}
```

### 왜 이렇게?

- `@RequiredRole(UserRole.STORE)` — 클래스 레벨에 적용하여 모든 메서드에 STORE 역할 검증
- `SessionUtils.getUserId()` — 세션에서 userId 추출. 컨트롤러에서 추출하고 서비스에는 userId만 전달 (서비스가 HttpServletRequest에 의존하지 않도록 계층 분리)
- 입점 신청 API는 **회원가입과 동시에 처리**되므로 이 컨트롤러에는 없음 (`UserController`에서 처리)

---

## 9. StoreAdminController

```java
@RestController
@RequestMapping("/api/v1/admin/stores")
@RequiredArgsConstructor
@RequiredRole(UserRole.ADMIN)
public class StoreAdminController {

    @GetMapping
    public ResponseEntity<List<StoreResponse>> getStores(@RequestParam(required = false) StoreStatus status) { ... }

    @GetMapping("/{storeId}")
    public ResponseEntity<StoreResponse> getStore(@PathVariable Long storeId) { ... }

    @PatchMapping("/{storeId}/approve")
    public ResponseEntity<StoreResponse> approve(@PathVariable Long storeId) { ... }

    @PatchMapping("/{storeId}/reject")
    public ResponseEntity<StoreResponse> reject(@PathVariable Long storeId) { ... }

    @PatchMapping("/{storeId}/suspend")
    public ResponseEntity<StoreResponse> suspend(@PathVariable Long storeId) { ... }
}
```

### 왜 이렇게?

- `@RequiredRole(UserRole.ADMIN)` — 클래스 레벨 적용. 모든 메서드가 ADMIN만 접근 가능
- **PATCH 사용**: 리소스의 일부(status)만 변경하므로 PUT이 아닌 PATCH가 REST 규약에 맞음
- **URL 설계**: `/admin/stores/{storeId}/approve` — 행위를 URL에 명시하여 의도가 명확함
- `@RequestParam(required = false) StoreStatus status` — 상태 필터링 선택적 적용. null이면 전체 조회

---

## 10. DTO

### StoreSignUpRequest

```java
public record StoreSignUpRequest(
    String name,            // 회원명
    String email,           // 이메일
    String password,        // 비밀번호
    String storeName,       // 스토어명
    String storeDescription // 스토어 설명
) {}
```

- 회원 정보 + 스토어 정보를 **하나의 요청**으로 받음
- **왜 birthDate가 없는가?**: 스토어 회원은 생일 정보가 불필요. User 엔티티의 birthDate 컬럼은 nullable이므로 문제 없음
- 각 필드에 `@Valid` 검증 어노테이션 적용 (이름 패턴, 이메일 형식, 비밀번호 정책, 스토어명 길이 등)

### StoreResponse

```java
@Builder
public record StoreResponse(
    Long storeId, String name, String description,
    StoreStatus status, LocalDateTime approvedAt, LocalDateTime createdAt
) {
    public static StoreResponse from(Store store) { ... }
}
```

- `from()` 정적 메서드로 엔티티 → DTO 변환
- Admin 조회, 내 스토어 조회 등 **스토어 정보가 필요한 모든 곳**에서 재사용

### StoreSignUpResponse

```java
@Builder
public record StoreSignUpResponse(
    Long userId, String name, String email, UserRole role,
    Long storeId, String storeName, StoreStatus storeStatus
) {
    public static StoreSignUpResponse from(User user, Store store) { ... }
}
```

- 회원 정보 + 스토어 정보를 **하나의 응답**으로 반환
- 스토어 회원가입 전용 응답 DTO

---

## 11. ErrorCode (Store 관련)

```java
// Store
STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "S001", "존재하지 않는 스토어입니다"),
STORE_ALREADY_EXISTS(HttpStatus.CONFLICT, "S002", "이미 입점 신청한 스토어가 존재합니다"),
INVALID_STORE_STATUS(HttpStatus.BAD_REQUEST, "S003", "현재 상태에서는 처리할 수 없습니다"),
UNAUTHORIZED_STORE_ACCESS(HttpStatus.FORBIDDEN, "S004", "본인 스토어만 접근할 수 있습니다"),
```

- **S001**: 존재하지 않는 storeId로 조회/승인/거절 시
- **S002**: 이미 스토어가 있는 사용자가 다시 신청 시 (1인 1스토어)
- **S003**: 잘못된 상태 전이 시 (예: REJECTED 상태에서 승인 시도)
- **S004**: 다른 사용자의 스토어에 접근 시 (추후 사용)

---

## 12. 권한 검증 구조

### @RequiredRole 어노테이션

```java
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiredRole {
    UserRole[] value();
}
```

- 클래스 레벨과 메서드 레벨 모두 적용 가능
- 메서드 레벨이 클래스 레벨보다 우선 적용

### RoleCheckInterceptor

```java
RequiredRole requiredRole = handlerMethod.getMethodAnnotation(RequiredRole.class);
if (requiredRole == null) {
    requiredRole = handlerMethod.getBeanType().getAnnotation(RequiredRole.class);
}
```

- 메서드 → 클래스 순서로 어노테이션 탐색
- 특정 메서드만 다른 역할이 필요하면 메서드에 `@RequiredRole`을 붙여 오버라이드 가능

---

## 13. 실행 플로우

### 스토어 회원가입 + 입점 신청

```
클라이언트                                       서버
   |                                              |
   |  POST /api/v1/users/sign-up/store            |
   |  {name, email, password, storeName, ...}      |
   | -------------------------------------------->|
   |                                              |
   |                              [AuthInterceptor] — 제외 경로이므로 통과
   |                                              |
   |                              [UserController.signUpWithStore()]
   |                                    |
   |                              1. userService.signUp() — 기존 회원가입 재사용
   |                                    → BCrypt 해싱 (트랜잭션 밖)
   |                                    → 트랜잭션: 검증 + User(STORE) INSERT
   |                                    → User 반환
   |                                    |
   |                              2. storeService.saveStore() — 입점 신청
   |                                    → 1인 1스토어 중복 검증
   |                                    → Store(PENDING) INSERT
   |                                    → Store 반환
   |                                    |
   |                              3. StoreSignUpResponse 조합
   |                                              |
   |  201 Created                                 |
   |  {userId, name, role:STORE, storeId,         |
   |   storeName, storeStatus:PENDING}            |
   | <--------------------------------------------|
```

### Admin 입점 승인

```
Admin                                            서버
   |                                              |
   |  PATCH /api/v1/admin/stores/1/approve        |
   | -------------------------------------------->|
   |                                              |
   |                              [AuthInterceptor] — 세션 확인 통과
   |                              [RoleCheckInterceptor] — ADMIN 역할 확인 통과
   |                                              |
   |                              [StoreAdminController.approve()]
   |                                    |
   |                              [StoreAdminService.changeStatus()]
   |                                    |
   |                              1. 트랜잭션 시작
   |                              2. Store 조회 (영속 상태)
   |                              3. store.approve() 호출
   |                                    → validateStatus(PENDING) — 상태 검증
   |                                    → status = APPROVED
   |                                    → approvedAt = now()
   |                              4. 트랜잭션 커밋 (dirty checking → UPDATE)
   |                                              |
   |  200 OK                                      |
   |  {storeId:1, status:APPROVED, approvedAt:...}|
   | <--------------------------------------------|
```

---

## 14. 예상 면접 질문

### 설계 관련

**Q: 스토어 회원가입에서 User와 Store가 별도 트랜잭션인데 원자성 문제는 없나요?**
> Store 저장 실패 가능성이 거의 없습니다. 방금 가입한 사용자이므로 1인 1스토어 중복은 불가능하고, DB 장애는 극히 드문 케이스입니다. Facade로 하나의 트랜잭션에 묶는 방법도 있지만, 기존 회원가입 로직을 재사용하기 위해 단순함을 선택했습니다. 만약 발생하더라도 STORE 역할 User가 스토어 없이 존재하는 것뿐이고, 재신청 로직으로 대응 가능합니다.

**Q: 왜 Facade 패턴을 사용하지 않았나요?**
> 초기에는 Facade로 User + Store를 하나의 트랜잭션에 묶었으나, `UserService.signUp()`의 BCrypt 해싱 + 예외 처리 로직이 Facade에 그대로 중복되는 문제가 있었습니다. 기존 `signUp()`을 그대로 호출하고 `saveStore()`만 추가 호출하는 방식이 중복 없이 깔끔합니다. 원자성과 단순함의 트레이드오프에서 현재 단계에선 단순함이 더 가치 있다고 판단했습니다.

**Q: 상태 변경 로직을 왜 엔티티에 넣었나요?**
> 상태 전이 규칙(PENDING → APPROVED, APPROVED → SUSPENDED 등)은 비즈니스 도메인의 핵심 규칙입니다. 서비스에서 `store.setStatus(APPROVED)`로 변경하면 잘못된 전이(REJECTED → APPROVED)를 서비스마다 검증해야 합니다. 엔티티 내부에 캡슐화하면 어디서 호출하든 규칙이 보장됩니다. 이것이 DDD의 Rich Domain Model 접근법입니다.

**Q: StoreService가 UserRepository를 사용하지 않는 이유는?**
> DDD에서 도메인 간 의존은 Repository가 아닌 Service 레이어를 통해야 합니다. Store 도메인이 User의 Repository를 직접 참조하면 도메인 경계가 무너집니다. 컨트롤러에서 UserService가 반환한 User 엔티티를 StoreService에 전달하는 방식으로 결합도를 낮췄습니다.

**Q: approve/reject/suspend를 Consumer로 추상화한 이유는?**
> 세 메서드의 구조가 동일합니다 (조회 → 상태 변경 → 응답 반환). 차이점은 상태 변경 동작뿐이므로 `Consumer<Store>`로 동작만 주입받아 DRY 원칙을 적용했습니다. 새로운 상태 변경이 추가되어도 `changeStatus()`를 재사용하면 됩니다.

### 성능 관련

**Q: BCrypt 해싱을 왜 트랜잭션 밖에서 실행하나요?**
> BCrypt는 의도적으로 느린 알고리즘으로 ~100ms 소요됩니다. 트랜잭션 안에서 실행하면 그 시간 동안 DB 커넥션을 점유합니다. 대용량 트래픽에서 동시에 100명이 회원가입하면 100개의 커넥션이 100ms씩 점유되어 커넥션 풀이 고갈될 수 있습니다. 트랜잭션 밖에서 해싱을 완료한 후 트랜잭션은 INSERT만 수행하면 커넥션 점유 시간을 최소화할 수 있습니다.

**Q: `@Transactional(readOnly = true)`를 사용한 이유는?**
> 읽기 전용 트랜잭션은 JPA의 dirty checking을 생략하고, DB에 따라 읽기 전용 커넥션이나 replica DB로 라우팅할 수 있습니다. 조회 성능을 최적화하고 추후 CQRS 패턴 적용 시 Command/Query DB 분리의 기반이 됩니다.

**Q: `findAll()`을 사용했는데 페이징은 왜 안 했나요?**
> 현재는 Admin API이고 스토어 수가 적어 수용 가능합니다. 다만 스토어가 수만 개로 늘어나면 OOM 위험이 있으므로 `Pageable`로 페이징 처리를 추가해야 합니다. 이 부분은 인지하고 있으며 추후 개선 예정입니다.

### 트랜잭션 관련

**Q: TransactionTemplate과 @Transactional의 차이는?**
> `@Transactional`은 선언적 트랜잭션으로 메서드 전체가 하나의 트랜잭션이 됩니다. `TransactionTemplate`은 프로그래밍 방식으로 트랜잭션 범위를 코드 블록 단위로 제어할 수 있습니다. BCrypt처럼 트랜잭션 밖에서 실행해야 할 로직이 있을 때 `TransactionTemplate`으로 범위를 정밀하게 제어합니다.

**Q: User 저장 후 Store 저장이 실패하면 어떻게 되나요?**
> 별도 트랜잭션이므로 User만 남습니다. 하지만 Store 저장 실패 가능성이 거의 없고 (방금 가입한 사용자 = 중복 불가), 발생 시 STORE 역할 User가 스토어 없이 존재하는 것뿐입니다. 필요하면 재신청 로직을 추가하거나, Facade 패턴으로 전환하여 원자성을 보장할 수 있습니다.

**Q: 별도 트랜잭션이면 데이터 정합성 문제 아닌가요?**
> 회원가입 직후 입점 신청이 실행되므로, 실패 가능한 경우는 1인 1스토어 중복(방금 가입한 사용자라 불가능)과 DB 장애(직전 INSERT가 성공했으므로 극히 드문 케이스)뿐입니다. Facade로 하나의 트랜잭션에 묶으면 원자성은 보장되지만, 기존 회원가입 로직(BCrypt 해싱 + 예외 처리)이 Facade에 중복되는 문제가 생깁니다. 실패 확률이 거의 없는 상황에서 코드 중복을 감수하는 것보다, 기존 API를 재사용하고 단순함을 유지하는 것이 현재 단계에서 더 합리적이라 판단했습니다. 추후 원자성이 반드시 필요해지면 Facade로 전환할 수 있습니다.

### 보안 관련

**Q: Admin API의 접근 제어는 어떻게 구현했나요?**
> `@RequiredRole(UserRole.ADMIN)`을 컨트롤러 클래스 레벨에 적용하고, `RoleCheckInterceptor`가 요청마다 세션의 역할을 확인합니다. 메서드 레벨 어노테이션이 클래스 레벨보다 우선 적용되어 특정 메서드만 다른 역할을 요구할 수도 있습니다.

**Q: PATCH로 상태를 변경하는데 동시에 두 Admin이 같은 스토어를 승인하면?**
> 첫 번째 요청이 PENDING → APPROVED로 변경 후 커밋합니다. 두 번째 요청은 이미 APPROVED 상태인 Store를 조회하고, `validateStatus(PENDING)`에서 상태 불일치로 `INVALID_STORE_STATUS` 예외가 발생합니다. 엔티티 레벨에서 보호됩니다.
