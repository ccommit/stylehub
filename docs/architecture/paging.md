# 100만건 상품 목록에서 Offset 대신 커서 페이징을 선택한 이유

## 문제 상황

StyleHub은 패션 이커머스 플랫폼이다. 상품이 100만건이 넘어가면서 상품 목록 조회 API의 페이징 방식을 결정해야 했다. 처음에는 익숙한 Offset 페이징(`LIMIT 20 OFFSET 999980`)을 고려했지만, 100만건 데이터로 실측해본 결과 Offset은 선택지가 될 수 없었다.

---

## 실측 결과 (100만건 기준)

| 조회 위치 | Offset | 커서 | 차이 |
|----------|--------|------|------|
| 첫 페이지 | 230ms | - | - |
| 50만번째 | 1,254ms | 156ms | 약 8배 |
| 100만번째 | 1,945ms | 94ms | 약 20배 |

Offset은 뒤로 갈수록 **230ms → 1,254ms → 1,945ms**로 급격히 느려졌다. 커서는 **156ms → 94ms**로 위치와 무관하게 일정했다.

약 2초면 사용자가 체감하는 수준이다. 무한 스크롤로 계속 내리는 쇼핑몰에서 뒤쪽 페이지가 2초씩 걸리면 사용자 이탈로 이어진다.

---

## 왜 Offset이 느린가

```sql
-- 100만번째 페이지
SELECT * FROM products ORDER BY product_id DESC LIMIT 20 OFFSET 999980;
```

이 쿼리가 실행되면 DB는 앞에서부터 999,980건을 하나씩 세면서 건너뛴다. 999,980건을 읽고 버린 뒤 20건만 반환하는 구조다. 데이터가 늘어날수록 버리는 양이 많아지니 느려질 수밖에 없다.

---

## 커서 페이징을 선택한 이유

```sql
-- 같은 위치를 커서로 조회
SELECT * FROM products WHERE product_id < 21 ORDER BY product_id DESC LIMIT 20;
```

커서는 `WHERE product_id < 21` 조건으로 B-Tree 인덱스에서 해당 위치로 바로 점프한다. 건너뛰는 행이 없다. 100만번째든 첫 페이지든 항상 20건만 읽는다.

StyleHub은 무신사처럼 무한 스크롤 기반 쇼핑몰이다. 사용자가 스크롤하면 프론트가 `nextCursor`로 다음 20건을 요청하고 기존 목록에 이어 붙이는 방식이다. "50페이지로 점프" 같은 기능이 필요 없으니 커서의 단점(페이지 점프 불가)이 우리 서비스에선 단점이 아니었다. 실제로 무신사, 쿠팡 등 대부분의 이커머스 플랫폼이 상품 목록에서 이 방식을 사용하고 있다.

---

## 구현 과정에서 겪은 문제

### JPQL의 null 파라미터 문제

처음에는 JPQL로 구현했다:

```java
@Query("SELECT p FROM Product p JOIN FETCH p.store " +
       "WHERE (:storeId IS NULL OR p.store.storeId = :storeId) " +
       "ORDER BY p.productId DESC")
```

`storeId=1`로 필터링해도 전체 상품이 조회됐다. Hibernate가 Long 타입의 null을 `IS NULL`로 정확히 평가하지 못하는 문제였다.

### QueryDSL로 전환

```java
BooleanBuilder builder = new BooleanBuilder();

if (storeId != null) {
    builder.and(product.store.storeId.eq(storeId));
}
```

QueryDSL의 `BooleanBuilder`는 null인 조건을 쿼리에 아예 포함하지 않는다. JPQL의 한계를 깔끔하게 해결했고, 필터 조건이 늘어나도 `if`만 추가하면 돼서 확장성도 좋았다.

---

## hasNext 판별 방식

다음 페이지 존재 여부를 알려면 보통 COUNT 쿼리를 날린다. 하지만 100만건에서 COUNT는 그 자체로 비용이다.

대신 **요청한 size + 1건을 조회**하는 방식을 선택했다:

```java
// 20건이 필요하면 21건 조회
List<Product> products = productQueryRepository.findProductsWithCursor(
        cursor, storeId, mainCategory, subCategory, pageSize + 1
);

// 21건이 왔으면 다음 페이지 있음, 20건 이하면 마지막 페이지
boolean hasNext = products.size() > pageSize;
```

COUNT 쿼리 없이 1건만 더 읽어서 판별한다. 100만건에서 COUNT 쿼리 한번을 아끼는 건 의미가 크다.

---

## 경량 목록 DTO

상품 상세 조회에는 옵션(색상, 사이즈, 재고)이 필요하지만 목록에서는 불필요하다. 목록에 옵션을 포함하면 상품 1건당 옵션 조회 쿼리가 추가로 나가서 N+1 문제가 발생한다.

그래서 목록용 경량 DTO를 별도로 만들었다:

```java
// 목록용 — 옵션 없이 가볍게
public record ProductListResponse(
    Long productId, Long storeId, String storeName,
    String name, MainCategory mainCategory, SubCategory subCategory,
    Integer price, String imageUrl
) {}

// 상세용 — 옵션 포함
public record ProductResponse(
    Long productId, Long storeId, String storeName,
    String name, ..., List<ProductOptionResponse> options
) {}
```

DTO를 분리하면서 목록 조회는 **Product + Store fetch join 1쿼리**로 끝난다.

---

## size 상한값 제한

처음에는 클라이언트가 보내는 size를 그대로 사용했다. 리뷰에서 `size=1000000`을 보내면 100만건을 한번에 조회하는 문제를 발견했다.

```java
private static final int MAX_PAGE_SIZE = 100;

int pageSize = (size != null && size > 0) ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
```

상한값을 100으로 제한해서 클라이언트가 아무리 큰 값을 보내도 최대 100건만 반환한다.

---

## 커서 페이징의 한계

만족하고 있지만 단점도 인지하고 있다:

**1. 중간 페이지 점프 불가**
"50페이지로 이동" 같은 기능을 구현할 수 없다. 하지만 무한 스크롤 UI에서는 페이지 번호 자체가 불필요하다.

**2. 정렬 기준 변경이 복잡**
현재는 최신순(product_id DESC)만 지원한다. 가격순, 인기순을 추가하려면 복합 커서가 필요하다:

```sql
-- 가격순 커서: price + product_id 조합
WHERE (price > :lastPrice) OR (price = :lastPrice AND product_id < :lastId)
```

필요해지면 도입할 예정이지만 지금은 YAGNI 원칙에 따라 최신순만 지원한다.

**3. 총 건수를 모름**
"전체 1,020,000건" 같은 정보를 표시할 수 없다. 필요하면 별도 COUNT API를 제공하거나 대략적인 수치를 캐싱할 수 있다.

---

## Offset을 쓸 곳은 있다

모든 곳에 커서를 쓰는 건 아니다. StyleHub의 Admin 입점 신청 목록 조회는 데이터가 적고 페이지 번호 이동이 유용할 수 있어서 Offset이 더 적합할 수 있다. 데이터 규모와 UI 요구사항에 맞게 선택하는 게 중요하다.

---

## 추후 최적화 방향

현재 구조에서 더 최적화할 수 있는 방향:

- **Redis 캐싱** — 인기 조회 조건(첫 페이지, 카테고리별 첫 페이지)을 캐싱하면 DB를 아예 안 탈 수 있다
- **커버링 인덱스** — 목록에 필요한 컬럼을 복합 인덱스로 만들면 테이블 접근 없이 인덱스만으로 조회 가능
- **CQRS + 읽기 전용 DB** — 현재 ProductViewService(Query)와 ProductService(Command)가 분리되어 있어 추후 Replica DB로 라우팅하면 쓰기 DB 부하를 줄일 수 있다

---

## 정리

| 항목 | Offset | 커서 |
|------|--------|------|
| 첫 페이지 | 230ms | - |
| 50만번째 | 1,254ms | 156ms |
| 100만번째 | **1,945ms** | **94ms** |
| 페이지 점프 | 가능 | 불가능 |
| 무한 스크롤 | 부적합 | 최적 |

100만건에서 Offset은 뒤쪽 페이지가 약 2초 걸렸고, 커서는 0.1초로 약 20배 빨랐다. 무한 스크롤 기반 패션 이커머스에서 커서 페이징은 자연스러운 선택이었다.
