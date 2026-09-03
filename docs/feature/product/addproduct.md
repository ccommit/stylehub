# 상품(옵션) 등록 및 재고 수정 API 구현 정리

## 1. 전체 아키텍처

### 왜 이렇게 설계했는가?

STORE 역할 사용자가 승인된 스토어에 상품과 옵션을 등록하고, 재고를 관리하는 API이다. 다음 원칙을 적용했다.

- **DDD 도메인 분리**: ProductService가 StoreRepository를 직접 참조하지 않고, StoreService를 통해 검증
- **엔티티 캡슐화**: 카테고리 매핑 검증은 SubCategory enum 내부, 재고 변경은 ProductOption 엔티티 내부
- **TransactionTemplate**: 트랜잭션 범위를 최소화하여 대용량 트래픽에서 커넥션 풀 고갈 방지
- **saveAll() 배치**: 옵션 N건을 한번에 INSERT하여 DB 호출 최소화
- **보안 검증**: 스토어 소유권 + 승인 상태 + 옵션-상품 소속 검증으로 URL 변조 방어

### 패키지 구조

```
product/
├── entity/
│   ├── Product.java              — 상품 엔티티
│   └── ProductOption.java        — 상품 옵션 엔티티 (색상, 사이즈, 재고)
├── enums/
│   ├── MainCategory.java         — 대분류 (SHOES, TOP, BOTTOM, ACCESSORY)
│   └── SubCategory.java          — 소분류 (12개, MainCategory 매핑)
├── repository/
│   ├── ProductRepository.java
│   └── ProductOptionRepository.java
├── dto/
│   ├── request/
│   │   ├── ProductCreateRequest.java    — 상품 + 옵션 등록 요청
│   │   ├── ProductOptionRequest.java    — 옵션 요청
│   │   └── StockUpdateRequest.java      — 재고 수정 요청
│   └── response/
│       ├── ProductResponse.java         — 상품 + 옵션 응답
│       └── ProductOptionResponse.java   — 옵션 응답
├── service/
│   └── ProductService.java       — 상품 등록, 재고 수정
└── controller/
    └── ProductController.java    — STORE 역할 API
```

### API 설계

| Method | URL | 설명 |
|--------|-----|------|
| POST | `/api/v1/stores/{storeId}/products` | 상품 + 옵션 등록 |
| PATCH | `/api/v1/stores/{storeId}/products/{productId}/options/{optionId}/stock` | 옵션별 재고 수정 |

---

## 2. Product 엔티티

```java
@Entity
@Table(name = "products")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 20)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "main_category", nullable = false)
    private MainCategory mainCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "sub_category", nullable = false)
    private SubCategory subCategory;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Integer price;

    @Column(name = "image_url", nullable = false, length = 300)
    private String imageUrl;

    @Column(name = "like_count")
    @Builder.Default
    private Integer likeCount = 0;
}
```

### 메서드별 역할

**`Product.create(Store, String, MainCategory, SubCategory, String, Integer, String)`**
- 정적 팩토리 메서드로 Product 생성
- 생성자 대신 사용하여 객체 생성 의도를 명확히 함
- likeCount는 `@Builder.Default`로 0 자동 설정

### 왜 이렇게?

- **`@ManyToOne(fetch = FetchType.LAZY)`**: 상품 조회 시 Store를 자동으로 JOIN하지 않음. N+1 방지
- **`@Enumerated(EnumType.STRING)`**: DB에 "SHOES"로 저장. ORDINAL이면 enum 순서 변경 시 데이터 깨짐
- **`columnDefinition = "TEXT"`**: description은 길이 제한 없는 텍스트. VARCHAR(255) 초과 대비
- **`@Builder.Default`**: Builder 패턴 사용 시 기본값이 무시되는 Lombok 이슈 방지

---

## 3. ProductOption 엔티티

```java
@Entity
@Table(name = "products_options")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_option_id")
    private Long productOptionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(length = 20)
    private String color;

    @Column(length = 10)
    private String size;

    @Column(name = "stock_quantity", nullable = false)
    @Builder.Default
    private Integer stockQuantity = 0;

    @Column(name = "max_point_amount")
    private Integer maxPointAmount;
}
```

### 메서드별 역할

**`ProductOption.create(Product, String, String, Integer, Integer)`**
- 정적 팩토리 메서드로 옵션 생성
- Product와의 연관관계를 생성 시점에 설정

**`updateStockQuantity(Integer stockQuantity)`**
- 재고 수량 변경. setter 대신 의미 있는 메서드명 사용
- dirty checking으로 트랜잭션 커밋 시 자동 UPDATE

### 왜 이렇게?

- **BaseEntity 미상속**: 옵션은 생성/수정 시간 추적이 불필요. 불필요한 필드를 갖지 않음
- **color, size nullable**: 악세서리는 사이즈 불필요, 단일 색상 상품은 color 불필요. 유연한 설계
- **setter 대신 `updateStockQuantity()`**: "재고를 변경한다"는 비즈니스 의도가 메서드명에 드러남

---

## 4. SubCategory Enum — MainCategory 매핑

```java
@Getter
public enum SubCategory {

    SNEAKERS(MainCategory.SHOES),
    DRESS_SHOES(MainCategory.SHOES),
    RUNNING_SHOES(MainCategory.SHOES),

    JACKET(MainCategory.TOP),
    SWEATSHIRT(MainCategory.TOP),
    T_SHIRT(MainCategory.TOP),

    DENIM_PANTS(MainCategory.BOTTOM),
    SKIRT(MainCategory.BOTTOM),
    SHORT_PANTS(MainCategory.BOTTOM),

    NECKLACE(MainCategory.ACCESSORY),
    RING(MainCategory.ACCESSORY),
    GLASSES(MainCategory.ACCESSORY);

    private final MainCategory mainCategory;

    SubCategory(MainCategory mainCategory) {
        this.mainCategory = mainCategory;
    }

    public boolean belongsTo(MainCategory mainCategory) {
        return this.mainCategory == mainCategory;
    }
}
```

### 왜 이렇게?

- **카테고리 조합 검증**: 신발 대분류에 티셔츠 소분류를 선택하는 잘못된 요청 방지
- **enum 내부에 매핑**: 서비스에서 if-else로 검증하지 않고, enum 자체가 규칙을 알고 있음
- **`belongsTo()` 메서드**: 검증 로직이 enum에 캡슐화되어 호출하는 쪽이 깔끔함

---

## 5. ProductService

```java
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final StoreService storeService;
    private final TransactionTemplate transactionTemplate;
}
```

### 메서드별 역할

**`registerProduct(Long userId, Long storeId, ProductCreateRequest request)`**

```java
public ProductResponse registerProduct(Long userId, Long storeId, ProductCreateRequest request) {
    validateCategoryCombination(request.mainCategory(), request.subCategory());

    record RegisterResult(Product product, List<ProductOption> options) {}

    RegisterResult result = Objects.requireNonNull(
            transactionTemplate.execute(status -> {
                Store store = storeService.findApprovedStoreByOwner(userId, storeId);
                Product savedProduct = saveProduct(store, ...);
                List<ProductOption> savedOptions = saveOptions(savedProduct, request.options());
                return new RegisterResult(savedProduct, savedOptions);
            })
    );

    return ProductResponse.from(result.product(), result.options());
}
```

1. `validateCategoryCombination()` — 카테고리 조합 검증 (트랜잭션 밖, DB 불필요)
2. `storeService.findApprovedStoreByOwner()` — 스토어 존재 + 소유권 + 승인 상태 검증
3. `saveProduct()` — Product 생성 및 저장
4. `saveOptions()` — ProductOption 리스트 생성 및 배치 저장
5. 응답 DTO 변환

**왜 카테고리 검증을 트랜잭션 밖에서?**
> DB가 필요 없는 검증을 트랜잭션 안에서 하면 커넥션을 불필요하게 점유함. 대용량 트래픽에서 커넥션 풀 고갈 방지

**왜 record RegisterResult?**
> TransactionTemplate에서 두 객체(Product + List<ProductOption>)를 반환하기 위한 구조적 제약. 상품과 옵션을 한 트랜잭션으로 묶기 위해 필요

**왜 한 트랜잭션?**
> 상품과 옵션은 함께 존재해야 의미가 있음. 옵션 저장 실패 시 상품도 롤백되어야 함

---

**`updateStock(Long userId, Long storeId, Long productId, Long optionId, Integer stockQuantity)`**

```java
public ProductOptionResponse updateStock(Long userId, Long storeId, Long productId,
                                          Long optionId, Integer stockQuantity) {
    ProductOption option = Objects.requireNonNull(
            transactionTemplate.execute(status -> {
                storeService.findApprovedStoreByOwner(userId, storeId);

                ProductOption target = productOptionRepository
                        .findByProductOptionIdAndProductProductId(optionId, productId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_OPTION_NOT_FOUND));

                target.updateStockQuantity(stockQuantity);
                return target;
            })
    );

    return ProductOptionResponse.from(option);
}
```

1. `findApprovedStoreByOwner()` — 스토어 소유권 검증
2. `findByProductOptionIdAndProductProductId()` — 옵션이 해당 상품에 속하는지 검증
3. `updateStockQuantity()` — 재고 변경 (dirty checking으로 자동 UPDATE)

**왜 optionId와 productId를 함께 검증?**
> optionId만 검증하면 다른 스토어의 옵션 재고를 변경할 수 있는 보안 결함. productId와 함께 검증하여 옵션이 해당 상품에 속하는지 확인. storeId로 스토어 소유권까지 검증하면 3단계 보안이 완성됨

**보안 검증 흐름:**
```
storeId → 본인 스토어 맞는지? (findApprovedStoreByOwner)
productId + optionId → 옵션이 해당 상품에 속하는지? (findByProductOptionIdAndProductProductId)
```

---

**`saveProduct(Store, String, MainCategory, SubCategory, String, Integer, String)`**

```java
private Product saveProduct(Store store, String name, MainCategory mainCategory,
                            SubCategory subCategory, String description, Integer price, String imageUrl) {
    Product product = Product.create(store, name, mainCategory, subCategory, description, price, imageUrl);
    return productRepository.save(product);
}
```

- DTO를 직접 받지 않고 원시 파라미터를 받음
- DTO 변경 시 이 메서드에 영향 없음 (결합도 감소)

---

**`saveOptions(Product, List<ProductOptionRequest>)`**

```java
private List<ProductOption> saveOptions(Product product, List<ProductOptionRequest> optionRequests) {
    List<ProductOption> options = new ArrayList<>(optionRequests.size());
    for (ProductOptionRequest request : optionRequests) {
        options.add(ProductOption.create(
                product, request.color(), request.size(),
                request.stockQuantity(), request.maxPointAmount()
        ));
    }
    return productOptionRepository.saveAll(options);
}
```

- **`ArrayList(optionRequests.size())`**: 초기 용량 지정으로 배열 확장 방지
- **`saveAll()`**: N건을 한번에 배치 INSERT. `save()` N번 호출보다 커넥션 점유 시간 감소
- **for문 사용**: stream보다 직관적이고 읽기 쉬움

---

**`validateCategoryCombination(MainCategory, SubCategory)`**

```java
private void validateCategoryCombination(MainCategory mainCategory, SubCategory subCategory) {
    if (!subCategory.belongsTo(mainCategory)) {
        throw new BusinessException(ErrorCode.INVALID_CATEGORY_COMBINATION);
    }
}
```

- 신발 대분류에 티셔츠 소분류 선택 방지
- DTO가 아닌 enum 값을 받아 DTO 의존 없음

---

## 6. StoreService.findApprovedStoreByOwner()

```java
/**
 * @throws BusinessException STORE_NOT_FOUND, UNAUTHORIZED_STORE_ACCESS, STORE_NOT_APPROVED
 */
public Store findApprovedStoreByOwner(Long userId, Long storeId) {
    Store store = storeRepository.findById(storeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

    if (!store.getUser().getUserId().equals(userId)) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED_STORE_ACCESS);
    }

    if (store.getStatus() != StoreStatus.APPROVED) {
        throw new BusinessException(ErrorCode.STORE_NOT_APPROVED);
    }

    return store;
}
```

### 왜 StoreService에 있는가?

- ProductService가 StoreRepository를 직접 참조하면 DDD 위반
- StoreService를 통해 소통하여 도메인 간 결합 방지
- 상품 등록, 재고 수정, 추후 상품 수정/삭제에서도 재사용 가능

### 3단계 검증

| 순서 | 검증 | 실패 시 |
|------|------|---------|
| 1 | 스토어 존재 여부 | 404 STORE_NOT_FOUND |
| 2 | 본인 스토어인지 (세션 userId vs Store userId) | 403 UNAUTHORIZED_STORE_ACCESS |
| 3 | 승인된 스토어인지 (APPROVED) | 403 STORE_NOT_APPROVED |

---

## 7. ProductController

```java
@RestController
@RequestMapping("/api/v1/stores/{storeId}/products")
@RequiredArgsConstructor
@RequiredRole(UserRole.STORE)
public class ProductController {

    private final ProductService productService;

    @PostMapping
    public ResponseEntity<ProductResponse> registerProduct(
            @PathVariable Long storeId,
            @Valid @RequestBody ProductCreateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.registerProduct(userId, storeId, request));
    }

    @PatchMapping("/{productId}/options/{optionId}/stock")
    public ResponseEntity<ProductOptionResponse> updateStock(
            @PathVariable Long storeId,
            @PathVariable Long productId,
            @PathVariable Long optionId,
            @Valid @RequestBody StockUpdateRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SessionUtils.getUserId(httpRequest);
        return ResponseEntity.ok(productService.updateStock(
                userId, storeId, productId, optionId, request.stockQuantity()));
    }
}
```

### 왜 이렇게?

- **`@RequiredRole(UserRole.STORE)`**: 클래스 레벨 적용. STORE 역할만 접근 가능
- **`@Valid`**: DTO 검증을 컨트롤러에서 처리. 잘못된 요청은 서비스까지 가지 않음
- **`SessionUtils.getUserId()`**: 세션에서 userId 추출. 서비스에 HttpServletRequest를 넘기지 않아 계층 분리 유지
- **PATCH 사용**: 재고(일부 필드)만 변경하므로 PUT이 아닌 PATCH가 REST 규약에 맞음
- **URL 설계**: `/stores/{storeId}/products/{productId}/options/{optionId}/stock` — 리소스 계층이 URL에 명확히 표현

---

## 8. DTO

### ProductCreateRequest

```java
public record ProductCreateRequest(
    @NotBlank @Size(max = 20) String name,
    @NotNull MainCategory mainCategory,
    @NotNull SubCategory subCategory,
    @NotBlank String description,
    @NotNull @Positive Integer price,
    @NotBlank @Size(max = 300) String imageUrl,
    @NotEmpty @Valid List<ProductOptionRequest> options
) {}
```

- **`@NotEmpty options`**: 옵션 없는 상품 등록 방지 (최소 1개)
- **`@Valid`**: 중첩 객체(ProductOptionRequest) 검증 활성화
- **Java record**: 불변 객체, getter 자동 생성, equals/hashCode 자동

### ProductOptionRequest

```java
public record ProductOptionRequest(
    @Size(max = 20) String color,
    @Size(max = 10) String size,
    @PositiveOrZero Integer stockQuantity,
    @PositiveOrZero Integer maxPointAmount
) {}
```

- **color, size nullable**: 악세서리는 사이즈 불필요. 유연한 설계
- **`@PositiveOrZero`**: 재고 0은 허용 (품절 상태), 음수는 차단

### ProductResponse

```java
@Builder
public record ProductResponse(
    Long productId, String name, MainCategory mainCategory, SubCategory subCategory,
    String description, Integer price, String imageUrl, LocalDateTime createdAt,
    List<ProductOptionResponse> options
) {
    public static ProductResponse from(Product product, List<ProductOption> options) { ... }
}
```

- **`from()` 정적 메서드**: 엔티티 → DTO 변환을 DTO가 담당. 서비스에서 변환 로직 분리
- **옵션 리스트 포함**: 상품과 옵션을 한 응답으로 반환. 클라이언트가 추가 API 호출 불필요

### StockUpdateRequest

```java
public record StockUpdateRequest(
    @NotNull @PositiveOrZero Integer stockQuantity
) {}
```

- 단일 필드. 재고 수량 변경만 담당하는 최소한의 DTO

---

## 9. ErrorCode (Product 관련)

```java
STORE_NOT_APPROVED(HttpStatus.FORBIDDEN, "P001", "입점 승인된 스토어만 상품을 등록할 수 있습니다"),
PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "P002", "존재하지 않는 상품입니다"),
INVALID_CATEGORY_COMBINATION(HttpStatus.BAD_REQUEST, "P003", "메인카테고리와 서브 카테고리가 일치하지 않습니다"),
PRODUCT_OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "P004", "존재하지 않는 상품 옵션입니다"),
```

- **P001**: PENDING/REJECTED/SUSPENDED 스토어에서 상품 등록 시도
- **P002**: 존재하지 않는 productId 접근
- **P003**: 대분류-소분류 불일치 (SHOES + T_SHIRT)
- **P004**: 존재하지 않는 옵션 또는 해당 상품에 속하지 않는 옵션

---

## 10. 실행 플로우

### 상품 등록

```
클라이언트                                          서버
   |                                                 |
   |  POST /api/v1/stores/1/products                 |
   |  Cookie: JSESSIONID=abc123                      |
   |  {name, mainCategory, subCategory, ..., options}|
   | ----------------------------------------------->|
   |                                                 |
   |                              [AuthInterceptor] — 세션 확인 통과
   |                              [RoleCheckInterceptor] — STORE 역할 확인
   |                                                 |
   |                              [ProductController.registerProduct()]
   |                                    |
   |                              1. SessionUtils.getUserId() — 세션에서 userId 추출
   |                                    |
   |                              [ProductService.registerProduct()]
   |                                    |
   |                              2. validateCategoryCombination() — 트랜잭션 밖
   |                                    → SHOES + T_SHIRT면 예외
   |                                    |
   |                              3. 트랜잭션 시작 ─────────────────┐
   |                                    |                          |
   |                              4. storeService                  |
   |                                 .findApprovedStoreByOwner()   |
   |                                    → 스토어 존재?              |
   |                                    → 본인 스토어?              |
   |                                    → APPROVED?                |
   |                                    |                          |
   |                              5. saveProduct()                 |
   |                                    → Product INSERT           |
   |                                    |                          |
   |                              6. saveOptions()                 |
   |                                    → ProductOption 배치 INSERT|
   |                                    |                          |
   |                              7. 트랜잭션 커밋 ────────────────┘
   |                                    (실패 시 전부 롤백)
   |                                    |
   |                              8. ProductResponse 생성
   |                                                 |
   |  201 Created                                    |
   |  {productId, name, options: [...]}              |
   | <-----------------------------------------------|
```

### 재고 수정

```
클라이언트                                          서버
   |                                                 |
   |  PATCH /stores/1/products/1/options/1/stock     |
   |  {stockQuantity: 100}                           |
   | ----------------------------------------------->|
   |                                                 |
   |                              [인증/역할 검증]
   |                                                 |
   |                              [ProductService.updateStock()]
   |                                    |
   |                              1. 트랜잭션 시작
   |                              2. 스토어 소유권 검증
   |                              3. optionId + productId 검증
   |                                    → 옵션이 해당 상품에 속하는지?
   |                              4. updateStockQuantity(100)
   |                              5. 트랜잭션 커밋 (dirty checking)
   |                                                 |
   |  200 OK                                         |
   |  {productOptionId, stockQuantity: 100, ...}     |
   | <-----------------------------------------------|
```

---

## 11. 보안 검증 구조

### 상품 등록 시
```
URL: /api/v1/stores/{storeId}/products

1단계: AuthInterceptor — 로그인했는지?
2단계: RoleCheckInterceptor — STORE 역할인지?
3단계: findApprovedStoreByOwner() — 본인의 승인된 스토어인지?
4단계: validateCategoryCombination() — 카테고리 조합이 올바른지?
```

### 재고 수정 시
```
URL: /api/v1/stores/{storeId}/products/{productId}/options/{optionId}/stock

1단계: AuthInterceptor — 로그인했는지?
2단계: RoleCheckInterceptor — STORE 역할인지?
3단계: findApprovedStoreByOwner() — 본인의 승인된 스토어인지?
4단계: findByProductOptionIdAndProductProductId() — 옵션이 해당 상품에 속하는지?
```

**URL 변조 시나리오:**
```
공격자(storeId=1)가 PATCH /stores/1/products/1/options/5/stock 요청
→ optionId=5가 productId=1에 속하지 않으면 PRODUCT_OPTION_NOT_FOUND
→ storeId=1이 본인 것이 아니면 UNAUTHORIZED_STORE_ACCESS
```

---

## 12. 예상 면접 질문

### 설계 관련

**Q1: 상품 등록에서 Product와 ProductOption을 한 트랜잭션으로 묶은 이유는?**
> 상품과 옵션은 함께 존재해야 의미가 있습니다. 옵션 저장이 실패하면 상품만 존재하게 되어 데이터 정합성이 깨집니다. 한 트랜잭션으로 묶어서 어느 하나라도 실패하면 전부 롤백되도록 보장했습니다.

**Q2: TransactionTemplate을 사용한 이유는? @Transactional과 차이는?**
> `@Transactional`은 메서드 전체가 트랜잭션이 됩니다. 하지만 카테고리 검증처럼 DB가 필요 없는 로직도 트랜잭션 안에 포함되어 커넥션을 불필요하게 점유합니다. `TransactionTemplate`으로 DB 작업 블록만 트랜잭션으로 묶어 커넥션 점유 시간을 최소화했습니다. 대용량 트래픽에서 커넥션 풀 고갈 방지 효과가 있습니다.

**Q3: ProductService가 StoreService를 의존하는 이유는?**
> DDD에서 도메인 간 의존은 Repository가 아닌 Service를 통해야 합니다. ProductService가 StoreRepository를 직접 참조하면 도메인 경계가 무너집니다. StoreService의 `findApprovedStoreByOwner()`를 호출하여 검증하면 Store 도메인의 내부 구현에 의존하지 않습니다.

**Q4: SubCategory에 MainCategory 매핑을 넣은 이유는?**
> 카테고리 조합 검증은 도메인 핵심 규칙입니다. 서비스에서 if-else로 검증하면 규칙이 흩어지고, 새 카테고리 추가 시 검증 코드를 빠뜨릴 수 있습니다. enum 자체가 규칙을 알고 있으면 `subCategory.belongsTo(mainCategory)` 한 줄로 검증이 완료됩니다.

**Q5: saveProduct()가 DTO 대신 원시 파라미터를 받는 이유는?**
> DTO는 컨트롤러-서비스 간 전달 객체입니다. private 메서드가 DTO를 알면 DTO 변경 시 영향을 받습니다. 원시 파라미터를 받으면 DTO와 결합이 끊어져 변경에 유연합니다.

**Q6: 엔티티에서 BusinessException을 던져도 되나요?**
> BusinessException은 Spring에 의존하지 않는 순수 RuntimeException입니다. 상태 전이 규칙은 도메인 핵심 로직이므로 엔티티에서 검증하고 예외를 던지는 게 DDD의 Rich Domain Model 접근법입니다. 서비스에서 검증하면 호출하는 모든 곳에서 검증을 반복해야 하고, 빠뜨릴 위험이 있습니다.

**Q7: URL에 storeId를 포함한 이유는? 세션에서 가져오면 안 되나요?**
> REST 규약에서 리소스의 계층 관계를 URL에 표현하는 게 정석입니다. `/stores/{storeId}/products`는 "이 스토어의 상품"이라는 의미가 명확합니다. 세션에서 가져오면 일반 사용자의 상품 조회 URL(`/products`)과 구분이 안 됩니다. URL 변조는 서버에서 소유권 검증으로 방어합니다.

### 성능 관련

**Q8: saveAll()을 사용한 이유는?**
> `save()`를 N번 호출하면 INSERT가 N번 실행되어 DB 커넥션 점유 시간이 늘어납니다. `saveAll()`은 내부적으로 배치 처리하여 DB 호출을 최소화합니다. 옵션이 10개면 INSERT 10번 vs 배치 1번의 차이입니다.

**Q9: ArrayList에 초기 용량을 지정한 이유는?**
> ArrayList는 기본 용량 10에서 부족하면 1.5배씩 확장하며 새 배열을 할당하고 복사합니다. 옵션 개수가 `optionRequests.size()`로 이미 정해져 있으므로 초기 용량을 지정하면 불필요한 배열 확장과 메모리 재할당이 발생하지 않습니다.

**Q10: 카테고리 검증을 트랜잭션 밖에서 하는 이유는?**
> 카테고리 검증은 enum 비교만 하면 되므로 DB가 필요 없습니다. 트랜잭션 안에서 하면 그 시간 동안 DB 커넥션을 점유합니다. 대용량 트래픽에서 불필요한 커넥션 점유를 줄이기 위해 트랜잭션 밖에서 실행합니다.

**Q11: 동시에 같은 옵션의 재고를 수정하면 어떻게 되나요?**
> 현재는 동시성 제어가 없어 lost update 문제가 발생할 수 있습니다. 재고 수정은 STORE 역할 사용자만 가능하고 동시 접근 가능성이 낮아 현재는 수용 가능합니다. 추후 주문 시 재고 차감에서는 낙관적 락(@Version) 또는 Redis 분산 락으로 동시성을 제어할 예정입니다.

**Q12: 상품 등록 시 N+1 문제는 없나요?**
> 등록은 INSERT만 하므로 N+1 문제가 발생하지 않습니다. N+1은 조회 시 연관 엔티티를 Lazy Loading으로 반복 조회할 때 발생합니다. 조회 API 구현 시 fetch join이나 EntityGraph로 대응할 예정입니다.

### 보안 관련

**Q13: URL의 storeId를 변조하면 어떻게 되나요?**
> `findApprovedStoreByOwner()`에서 세션의 userId와 Store의 userId를 비교합니다. 다른 사람의 storeId를 넣어도 본인 스토어가 아니면 `UNAUTHORIZED_STORE_ACCESS(403)`가 반환됩니다.

**Q14: optionId를 변조해서 다른 상품의 재고를 변경할 수 있나요?**
> `findByProductOptionIdAndProductProductId(optionId, productId)`로 옵션이 해당 상품에 속하는지 검증합니다. 다른 상품의 옵션 ID를 넣으면 `PRODUCT_OPTION_NOT_FOUND(404)`가 반환됩니다.

**Q15: PENDING 상태 스토어에서 상품을 등록할 수 있나요?**
> 불가능합니다. `findApprovedStoreByOwner()`에서 `store.getStatus() != APPROVED`이면 `STORE_NOT_APPROVED(403)`를 던집니다. 승인된 스토어만 상품 등록이 가능합니다.

### 구조 관련

**Q16: for문 대신 stream을 사용하지 않은 이유는?**
> 단순 변환 + 리스트 추가 작업이라 stream은 과합니다. for문이 더 직관적이고 읽기 쉽습니다. stream은 필터링, 그룹핑 등 복잡한 변환에 적합합니다.

**Q17: registerProduct()의 책임이 많지 않나요?**
> 카테고리 검증, 스토어 검증, 상품 저장, 옵션 저장, 응답 변환을 하지만 각각 별도 메서드로 분리되어 있습니다. registerProduct()는 이 흐름을 오케스트레이션하는 역할만 합니다. 하나의 유스케이스를 관리하는 서비스 메서드로서 적절한 수준입니다.

**Q18: ProductOption이 BaseEntity를 상속하지 않는 이유는?**
> 옵션은 생성/수정 시간 추적이 불필요합니다. 불필요한 필드를 갖지 않는 것이 설계 원칙입니다. 필요해지면 그때 추가하면 됩니다 (YAGNI).

**Q19: CascadeType.PERSIST를 사용하지 않은 이유는?**
> cascade를 사용하면 Product 엔티티에 `List<ProductOption>`을 두어야 하고 양방향 연관관계가 됩니다. 현재 단방향(ProductOption → Product)이 더 단순하고, INSERT 2번이지만 같은 트랜잭션 안이라 커넥션은 1번만 사용합니다.

**Q20: 재고를 0으로 설정하면 어떻게 되나요?**
> `@PositiveOrZero`로 0은 허용됩니다. 재고 0은 품절 상태를 의미하며, 추후 주문 시 재고가 0이면 주문을 거부하는 로직에서 활용됩니다.
