# 상품 단건 등록 API 개발 - 단계별 프롬프트

## 프로젝트 컨벤션 (모든 단계에 적용)
- 패키지: ccommit.stylehub
- 명시적 개별 import (와일드카드 import 금지)
- Javadoc 헤더 필수 (@author WonJin Bae, @created 날짜, 목적 설명)
- DTO는 Java record 사용
- Response DTO는 @Builder + from() 정적 메서드
- @RequiredArgsConstructor로 의존성 주입
- FetchType.LAZY 연관관계
- @SuperBuilder + @NoArgsConstructor(access = AccessLevel.PROTECTED) 엔티티 패턴
- static factory method create() 패턴
- BusinessException + ErrorCode로 예외 처리
- 대용량 트래픽 고려 (TransactionTemplate으로 트랜잭션 범위 최소화)

## 기존 코드 현황
- Product 엔티티: 이미 존재 (store, name, mainCategory, subCategory, description, price, imageUrl, likeCount)
- ProductOption 엔티티: 이미 존재 (product, color, size, stockQuantity, maxPointAmount)
  - 주의: 와일드카드 import(`jakarta.persistence.*`) 사용 중 → 수정 필요
- MainCategory enum: SHOES, TOP, BOTTOM, ACCESSORY
- SubCategory enum: 대분류별 3개씩 총 12개
- Store 엔티티: status 필드로 APPROVED 검증 가능
- StoreRepository: existsByUserUserId(), findByUserUserId() 존재
- ErrorCode: STORE_NOT_FOUND, UNAUTHORIZED_STORE_ACCESS 이미 존재

---

## 1단계: ProductOption 엔티티 수정
ProductOption.java의 와일드카드 import를 명시적 import로 수정해줘.
- `jakarta.persistence.*` → 개별 import로 교체
- 기존 필드, 메서드, 어노테이션은 그대로 유지

## 2단계: MainCategory-SubCategory 매핑
SubCategory enum에 MainCategory 연관 필드를 추가해줘.
- 각 SubCategory가 어떤 MainCategory에 속하는지 매핑
- `SubCategory.belongsTo(MainCategory)` 검증 메서드 추가
- 예: SNEAKERS → SHOES, T_SHIRT → TOP
- 잘못된 조합 시 검증에 사용 (신발 대분류에 티셔츠 소분류 선택 방지)

## 3단계: ErrorCode 추가
상품 관련 ErrorCode를 ErrorCode enum에 추가해줘.
- STORE_NOT_APPROVED(HttpStatus.FORBIDDEN, "P001", "입점 승인된 스토어만 상품을 등록할 수 있습니다")
- PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "P002", "존재하지 않는 상품입니다")
- INVALID_CATEGORY_COMBINATION(HttpStatus.BAD_REQUEST, "P003", "대분류와 소분류가 일치하지 않습니다")
- 기존 UNAUTHORIZED_STORE_ACCESS(S004)는 본인 스토어 검증에 재사용

## 4단계: Repository 생성
Product, ProductOption에 대한 JpaRepository를 생성해줘.
- 패키지: ccommit.stylehub.product.repository
- ProductRepository: JpaRepository<Product, Long>
- ProductOptionRepository: JpaRepository<ProductOption, Long>
- 현재 단건 등록에 필요한 메서드만 정의 (추가 조회 메서드는 조회 API 구현 시 추가)

## 5단계: DTO 설계
상품 등록 요청/응답 DTO를 설계해줘.
- 패키지: ccommit.stylehub.product.dto.request, ccommit.stylehub.product.dto.response

### ProductCreateRequest (record)
- name: @NotBlank, @Size(max = 20)
- mainCategory: @NotNull (MainCategory enum)
- subCategory: @NotNull (SubCategory enum)
- description: @NotBlank
- price: @NotNull, @Positive
- imageUrl: @NotBlank, @Size(max = 300)
- options: @NotEmpty, @Valid List<ProductOptionRequest>

### ProductOptionRequest (record)
- color: @Size(max = 20)
- size: @Size(max = 10)
- stockQuantity: @NotNull, @PositiveOrZero
- maxPointAmount: @PositiveOrZero (nullable)

### ProductResponse (record + @Builder)
- productId, name, mainCategory, subCategory, description, price, imageUrl, createdAt
- options: List<ProductOptionResponse>
- from(Product, List<ProductOption>) 정적 메서드

### ProductOptionResponse (record + @Builder)
- productOptionId, color, size, stockQuantity, maxPointAmount
- from(ProductOption) 정적 메서드

## 6단계: Service 구현
ProductService를 구현해줘.
- 패키지: ccommit.stylehub.product.service
- 의존성: ProductRepository, ProductOptionRepository, StoreRepository (Store 조회 및 검증에만 사용)

### registerProduct(Long userId, Long storeId, ProductCreateRequest request) 메서드
- TransactionTemplate으로 트랜잭션 관리
- 트랜잭션 내에서:
  1. storeRepository.findById(storeId) → STORE_NOT_FOUND
  2. store.getUser().getUserId()와 userId 비교 → UNAUTHORIZED_STORE_ACCESS
  3. store.getStatus() == APPROVED 검증 → STORE_NOT_APPROVED
  4. SubCategory.belongsTo(MainCategory) 검증 → INVALID_CATEGORY_COMBINATION
  5. Product.create()로 상품 생성 및 저장
  6. 각 옵션을 ProductOption.create()로 생성 및 저장
  7. ProductResponse 반환

### DDD 관련 참고
- StoreRepository를 직접 사용하는 것은 Store 조회 및 권한 검증 목적
- Store 도메인의 비즈니스 로직을 호출하지 않으므로 수용 가능한 수준
- 대안으로 StoreService에 검증 메서드를 두고 호출할 수도 있으나, 단순 조회+검증이라 현재는 직접 사용

## 7단계: Controller 구현
ProductController를 구현해줘.
- 패키지: ccommit.stylehub.product.controller
- @RestController, @RequestMapping("/api/v1/stores/{storeId}/products")
- @RequiredRole(UserRole.STORE) 클래스 레벨 적용

### POST 메서드
- @Valid @RequestBody ProductCreateRequest
- @PathVariable Long storeId
- HttpServletRequest로 SessionUtils.getUserId() 추출
- productService.registerProduct(userId, storeId, request) 호출
- ResponseEntity<ProductResponse> 반환, HttpStatus.CREATED
