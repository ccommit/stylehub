# 주문 API + 회원 주문 내역 API 구현 정리

## 1. 전체 구조

```
OrderController (API 엔드포인트)
  → OrderService (흐름 관리, 트랜잭션 없음)
    → OrderTransactionService (@Transactional — 주문 생성/취소)
      → UserService (배송지/유저 검증 — DDD)
      → ProductService (재고 차감 — DDD)
    → OrderTimeoutManager (Redis ZSET 타이머 — 트랜잭션 밖)
  → OrderViewService (@Transactional(readOnly = true) — 조회 전용 CQRS)
    → OrderQueryRepository (QueryDSL 커서 페이징)
```

주문 도메인은 4개의 서비스로 분리했다:
- **OrderService** — 흐름 관리. 트랜잭션 없이 OrderTransactionService와 Redis를 조합
- **OrderTransactionService** — DB 작업. @Transactional로 주문 생성/취소의 원자성 보장
- **OrderViewService** — 조회 전용. @Transactional(readOnly = true)로 CQRS Query 담당
- **OrderTimeoutManager** — Redis ZSET 타이머 등록/제거

### 왜 이렇게 분리했는가

같은 클래스에서 @Transactional 메서드를 호출하면 Spring 프록시가 적용되지 않아 트랜잭션이 걸리지 않는다. OrderService와 OrderTransactionService를 별도 클래스로 분리하여 프록시를 보장했다. 추후 토스 결제 API 호출이 트랜잭션 밖에서 실행되어야 하는데, 이 구조 덕분에 DB 작업만 트랜잭션에 묶고 외부 HTTP 호출은 트랜잭션 밖에서 처리할 수 있다.

### API

| Method | URL | 설명 | 역할 |
|--------|-----|------|------|
| POST | `/api/v1/orders` | 주문 생성 | USER |
| GET | `/api/v1/orders` | 내 주문 내역 목록 (커서 페이징) | USER |
| GET | `/api/v1/orders/{orderId}` | 주문 상세 조회 | USER (본인만) |

---

## 2. OrderController

```java
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderViewService orderViewService;
```

- `@RequiredRole` 없음 — 일반 USER가 사용하는 API. 인증은 AuthInterceptor에서 세션으로 확인
- OrderService(생성)와 OrderViewService(조회)를 분리하여 CQRS 유지

### 주문 생성

```java
@PostMapping
public ResponseEntity<OrderResponse> createOrder(
        @Valid @RequestBody OrderCreateRequest request,
        HttpServletRequest httpRequest) {
    Long userId = SessionUtils.getUserId(httpRequest);
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(orderService.createOrder(userId, request));
}
```

- `SessionUtils.getUserId()` — 세션에서 userId 추출. 서비스에 HttpServletRequest를 넘기지 않아 계층 분리 유지
- `@Valid` — DTO 검증을 컨트롤러에서 처리. 잘못된 요청은 서비스까지 가지 않음
- `HttpStatus.CREATED` — 새 리소스(Order) 생성이므로 201

### 내 주문 내역 목록

```java
@GetMapping
public ResponseEntity<OrderCursorResponse> getMyOrders(
        @RequestParam(required = false) Long cursor,
        @RequestParam(required = false) Integer size,
        HttpServletRequest httpRequest) {
    Long userId = SessionUtils.getUserId(httpRequest);
    return ResponseEntity.ok(orderViewService.getMyOrders(userId, cursor, size));
}
```

- 커서 기반 무한 스크롤. cursor와 size 모두 선택적
- OrderViewService(Query)를 호출하여 CQRS 유지

### 주문 상세 조회

```java
@GetMapping("/{orderId}")
public ResponseEntity<OrderResponse> getOrder(
        @PathVariable Long orderId,
        HttpServletRequest httpRequest) {
    Long userId = SessionUtils.getUserId(httpRequest);
    return ResponseEntity.ok(orderViewService.getOrder(userId, orderId));
}
```

- 본인 주문만 조회 가능. OrderViewService에서 userId로 소유권 검증

---

## 3. OrderService — 흐름 관리

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderTransactionService orderTransactionService;
    private final OrderTimeoutManager orderTimeoutManager;
```

- 트랜잭션 없음 — 이 클래스 자체에 @Transactional이 없다
- OrderTransactionService를 호출하면 그쪽의 @Transactional이 프록시를 통해 적용됨
- OrderTimeoutManager는 Redis 작업이라 트랜잭션 밖에서 실행

### createOrder()

```java
@ExecutionTimeCheck(threshold = 3000)
public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
```

- `@ExecutionTimeCheck(threshold = 3000)` — 주문 생성이 3초를 초과하면 WARN 로그. 대용량 트래픽에서 병목을 조기 발견

```java
    // === 트랜잭션 안 ===
    OrderResponse orderResponse = orderTransactionService.createOrder(userId, request);
```

- OrderTransactionService의 @Transactional 프록시가 동작하여 트랜잭션 시작
- 배송지 검증 + 주문 생성 + 재고 차감이 한 트랜잭션으로 묶임
- 여기서 커밋되면 DB 커넥션이 반환됨

```java
    // === 트랜잭션 밖 (DB 커넥션 반환된 상태) ===
    orderTimeoutManager.registerTimeout(orderResponse.orderId());
```

- 트랜잭션 커밋 후에 Redis ZSET에 타이머 등록
- 트랜잭션 밖에서 하는 이유: 트랜잭션이 롤백되면 Redis에만 등록되는 문제 방지
- score = 현재 시각 + 30분. 30분 후에 OrderTimeoutScheduler가 만료를 감지하여 자동 취소

```java
    // TODO: 토스페이먼츠 결제 API 호출 (트랜잭션 밖 — 커넥션 점유 안 함)
    // TODO: 위변조 검증
    // TODO: 결제 성공 시 타이머 제거
    // TODO: 결제 실패 시 주문 취소 + 재고 복구 + 타이머 제거
```

- 결제 API는 트랜잭션 밖에서 호출. 수초 걸려도 커넥션 점유 0
- 이 구조가 가능한 이유가 서비스 분리

---

## 4. OrderTransactionService — 트랜잭션 단위

```java
@Service
@RequiredArgsConstructor
public class OrderTransactionService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserService userService;
    private final ProductService productService;
```

- 다른 도메인 접근은 UserService, ProductService를 통해서만. Repository 직접 참조 없음 (DDD)
- OrderRepository, OrderItemRepository만 직접 참조 — 자기 도메인

### createOrder()

```java
@Transactional
public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
    Address address = userService.findAddressByOwner(userId, request.addressId());
    User user = address.getUser();
```

- `findAddressByOwner()` — 배송지 조회 + 본인 검증. fetch join으로 User도 함께 가져옴
- `address.getUser()` — fetch join 덕분에 추가 쿼리 없이 User 반환. 별도 User 조회 제거로 DB 접근 1회 절약

```java
    Order savedOrder = orderRepository.save(Order.create(user, address));
```

- `Order.create()` — pgOrderId를 `ORD-날짜-UUID` 형식으로 자동 생성, 상태는 PENDING
- INSERT 1회

```java
    List<OrderItem> savedItems = decreaseStockAndCreateItems(savedOrder, request.items());
    return OrderResponse.from(savedOrder, savedItems);
}
```

### decreaseStockAndCreateItems()

```java
private List<OrderItem> decreaseStockAndCreateItems(Order order, List<OrderItemRequest> itemRequests) {
    List<OrderItemRequest> merged = mergeAndSort(itemRequests);
```

- 먼저 같은 옵션을 합산하고 정렬. 이유는 두 가지:
  1. **중복 옵션 합산** — optionId=1이 2번 들어오면 수량을 합산하여 락 1번만 획득
  2. **deadlock 방지** — 오름차순 정렬로 모든 트랜잭션이 같은 순서로 락 획득

```java
    List<OrderItem> items = new ArrayList<>(merged.size());
    for (OrderItemRequest request : merged) {
        ProductOption option = productService.decreaseStockWithLock(
                request.productOptionId(), request.quantity()
        );
```

- `decreaseStockWithLock()` — 비관적 락(SELECT FOR UPDATE)으로 재고 행 잠금
- fetch join으로 Product + Store도 함께 가져와 N+1 방지
- `option.decreaseStock(quantity)` — 엔티티 내부에서 재고 부족 검증. 부족하면 INSUFFICIENT_STOCK 예외 → 트랜잭션 롤백
- @Transactional 없어서 현재 트랜잭션에 자연스럽게 참여

```java
        items.add(OrderItem.create(
                option, order, request.quantity(),
                option.getProduct().getPrice(), null
        ));
    }
    return orderItemRepository.saveAll(items);
}
```

- `option.getProduct().getPrice()` — fetch join으로 이미 로딩됨. 추가 쿼리 없음
- `saveAll()` — 배치 INSERT로 N건을 한번에 저장

### mergeAndSort()

```java
private List<OrderItemRequest> mergeAndSort(List<OrderItemRequest> itemRequests) {
    Map<Long, Integer> merged = new TreeMap<>();
    for (OrderItemRequest request : itemRequests) {
        merged.merge(request.productOptionId(), request.quantity(), Integer::sum);
    }
```

- `TreeMap` — Key(optionId) 오름차순 자동 정렬 + `merge()`로 같은 키의 수량 합산
- 정렬과 합산을 동시에 처리하는 자료구조 선택

```java
    List<OrderItemRequest> result = new ArrayList<>(merged.size());
    for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
        result.add(new OrderItemRequest(entry.getKey(), entry.getValue()));
    }
    return result;
}
```

- TreeMap의 entrySet은 키 오름차순이므로 결과도 정렬됨

### cancelOrder()

```java
@Transactional
public void cancelOrder(Long orderId) {
    Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    order.cancel();
```

- `order.cancel()` — 엔티티 내부에서 PENDING에서만 취소 가능하도록 검증. 다른 상태면 INVALID_ORDER_STATUS 예외

```java
    List<OrderItem> items = orderItemRepository.findByOrderOrderId(orderId);
    for (OrderItem item : items) {
        productService.increaseStock(
                item.getProductOption().getProductOptionId(),
                item.getQuantity()
        );
    }
}
```

- 각 주문 항목의 재고를 복구. ProductService를 통해 소통 (DDD)
- 주문 취소 + 재고 복구가 한 트랜잭션. 재고 복구 실패 시 취소도 롤백

---

## 5. OrderViewService — 조회 전용 (CQRS Query)

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderViewService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
```

- 클래스 레벨 `@Transactional(readOnly = true)` — 모든 메서드에 적용. dirty checking 생략, 읽기 전용 커넥션 사용
- `MAX_PAGE_SIZE = 100` — 클라이언트가 size=999999 보내도 100건 제한 (OOM 방지)

### getMyOrders()

```java
public OrderCursorResponse getMyOrders(Long userId, Long cursor, Integer size) {
    int pageSize = (size != null && size > 0) ? Math.min(size, MAX_PAGE_SIZE) : DEFAULT_PAGE_SIZE;
    List<Order> orders = orderQueryRepository.findMyOrdersWithCursor(userId, cursor, pageSize + 1);
```

- `pageSize + 1` — 21건 조회해서 21건이면 hasNext=true, 20건 이하면 마지막. COUNT 쿼리 없이 판별
- QueryDSL로 동적 커서 조건 처리

```java
    List<Long> orderIds = orders.stream().map(Order::getOrderId).toList();
    Map<Long, Integer> totalAmountMap = getTotalAmountMap(orderIds);
```

- 주문 ID 목록으로 총액을 **한번에** 조회. 주문 20건이면 SELECT 1번
- 이전에는 for문에서 `calculateTotalAmount()` 20번 호출 → N+1 문제. GROUP BY로 해결

```java
    List<OrderListResponse> orderList = new ArrayList<>(orders.size());
    for (Order order : orders) {
        Integer totalAmount = totalAmountMap.getOrDefault(order.getOrderId(), 0);
        orderList.add(OrderListResponse.from(order, totalAmount));
    }
    return OrderCursorResponse.of(orderList, pageSize);
}
```

- Map에서 O(1)로 총액 조회하여 OrderListResponse 생성

### getTotalAmountMap()

```java
private Map<Long, Integer> getTotalAmountMap(List<Long> orderIds) {
    if (orderIds.isEmpty()) {
        return Map.of();
    }
    Map<Long, Integer> map = new HashMap<>(orderIds.size());
    for (Object[] row : orderItemRepository.calculateTotalAmounts(orderIds)) {
        map.put((Long) row[0], ((Number) row[1]).intValue());
    }
    return map;
}
```

- `calculateTotalAmounts()` — `SELECT order_id, SUM(quantity * unit_price) GROUP BY order_id WHERE order_id IN (:ids)`
- N개 주문의 총액을 1쿼리로 가져옴

### getOrder()

```java
public OrderResponse getOrder(Long userId, Long orderId) {
    Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

    if (!order.getUser().getUserId().equals(userId)) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED_ORDER_ACCESS);
    }
```

- 본인 주문만 접근 가능. 다른 사람의 orderId 넣으면 403

```java
    List<OrderItem> items = orderItemRepository.findByOrderIdWithDetails(orderId);
    return OrderResponse.from(order, items);
}
```

- `findByOrderIdWithDetails()` — OrderItem + ProductOption + Product + Store를 fetch join으로 한 쿼리
- 상세 조회에서만 옵션/상품/스토어 정보를 가져옴 (목록에서는 가져오지 않음)

---

## 6. OrderTimeoutScheduler — Redis ZSET 폴링

```java
@Component
@RequiredArgsConstructor
public class OrderTimeoutScheduler {

    public static final String ORDER_TIMEOUT_KEY = "order:timeout";
```

### cancelExpiredOrders()

```java
@Scheduled(fixedDelay = 1000)
public void cancelExpiredOrders() {
    long now = System.currentTimeMillis();
    Set<ZSetOperations.TypedTuple<String>> expiredOrders =
            redisTemplate.opsForZSet().rangeByScoreWithScores(ORDER_TIMEOUT_KEY, 0, now);
```

- 1초마다 실행. Redis ZRANGEBYSCORE로 score <= 현재 시각인 주문만 조회
- Redis 메모리 조회라 100만건이어도 ~1ms. DB 스캔 없음

```java
    for (ZSetOperations.TypedTuple<String> tuple : expiredOrders) {
        Long orderId = Long.valueOf(tuple.getValue());
        try {
            Long removed = redisTemplate.opsForZSet().remove(ORDER_TIMEOUT_KEY, orderIdStr);
            if (removed == null || removed == 0) {
                continue;
            }
```

- ZREM 먼저 실행 — 원자적 연산이라 멀티 서버에서 중복 처리 방지
- removed가 0이면 다른 서버가 이미 처리한 것

```java
            orderTransactionService.cancelOrder(orderId);
```

- 별도 트랜잭션으로 주문 취소 + 재고 복구
- try-catch로 하나가 실패해도 나머지 정상 처리

---

## 7. OrderTimeoutManager — 타이머 등록/제거

### registerTimeout()

```java
public void registerTimeout(Long orderId) {
    double expireAt = System.currentTimeMillis() + TIMEOUT_MILLIS;
    redisTemplate.opsForZSet().add(ORDER_TIMEOUT_KEY, String.valueOf(orderId), expireAt);
}
```

- score = 현재 시각 + 30분(만료 시각)
- OrderService에서 트랜잭션 커밋 후 호출

### removeTimeout()

```java
public void removeTimeout(Long orderId) {
    redisTemplate.opsForZSet().remove(ORDER_TIMEOUT_KEY, String.valueOf(orderId));
}
```

- 결제 완료 또는 주문 취소 시 타이머 즉시 제거
- ZREM으로 제거하면 Scheduler가 더 이상 이 주문을 찾지 않음

---

## 8. Order 엔티티

### create()

```java
public static Order create(User user, Address address) {
    return Order.builder()
            .pgOrderId(generatePgOrderId())
            .user(user)
            .address(address)
            .orderStatus(OrderStatus.PENDING)
            .build();
}
```

- `pgOrderId` — `ORD-20260327-a3f8b2c1` 형식. 토스페이먼츠 연동 시 주문번호로 활용
- 생성 시 PENDING 상태. 결제 완료 시 PAID로 전환

### generatePgOrderId()

```java
private static String generatePgOrderId() {
    String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    String uuid = UUID.randomUUID().toString().substring(0, 8);
    return "ORD-" + date + "-" + uuid;
}
```

- 날짜가 포함되어 언제 생성된 주문인지 바로 파악 가능
- UUID 앞 8자리로 중복 방지. 총 22자

### cancel()

```java
public void cancel() {
    if (this.orderStatus != OrderStatus.PENDING) {
        throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
    }
    this.orderStatus = OrderStatus.CANCELLED;
}
```

- PENDING에서만 취소 가능. PAID나 DELIVERED에서 취소하면 예외
- 상태 전이 규칙을 엔티티가 캡슐화. 서비스에서 if 반복 불필요

---

## 9. ProductOption — 재고 차감/복구

### decreaseStock()

```java
public void decreaseStock(int quantity) {
    if (this.stockQuantity < quantity) {
        throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
    }
    this.stockQuantity -= quantity;
}
```

- 재고 부족 검증을 엔티티 내부에서 처리. 어디서 호출해도 규칙 보장
- 비관적 락이 걸린 상태에서 실행되므로 동시성 안전

### increaseStock()

```java
public void increaseStock(int quantity) {
    this.stockQuantity += quantity;
}
```

- 주문 취소 시 재고 복구

---

## 10. DTO

### OrderCreateRequest

```java
public record OrderCreateRequest(
        @NotNull(message = "배송지는 필수입니다") Long addressId,
        @NotEmpty(message = "주문 항목은 최소 1개 이상이어야 합니다") @Valid List<OrderItemRequest> items
) {}
```

- `@Valid List` — 중첩 객체(OrderItemRequest) 검증 활성화
- `@NotEmpty` — 빈 배열 방지

### OrderItemRequest

```java
public record OrderItemRequest(
        @NotNull Long productOptionId,
        @NotNull @Positive Integer quantity
) {}
```

- `@Positive` — 0과 음수 방지. 1 이상만 허용

### OrderResponse — 주문 상세

```java
public static OrderResponse from(Order order, List<OrderItem> items) {
    int totalAmount = itemResponses.stream().mapToInt(OrderItemResponse::totalPrice).sum();
    int finalAmount = totalAmount - order.getDiscountAmount() - order.getUsedPoint();
```

- `totalAmount` — 상품 총액 (옵션별 수량 × 단가의 합)
- `finalAmount` — 최종 결제 금액 (총액 - 쿠폰 - 포인트). 현재 쿠폰/포인트 미구현이라 totalAmount와 동일
- discountAmount, usedPoint, earnedPoint 필드를 미리 포함하여 추후 수정 없이 반영

### OrderListResponse — 주문 목록 경량

```java
public static OrderListResponse from(Order order, Integer totalAmount) {
```

- 주문 항목(items) 미포함 — N+1 방지
- totalAmount는 `calculateTotalAmounts()` GROUP BY로 한번에 계산한 값

### OrderCursorResponse — 커서 페이징

```java
public static OrderCursorResponse of(List<OrderListResponse> orders, int size) {
    boolean hasNext = orders.size() > size;
    Long nextCursor = hasNext ? content.get(content.size() - 1).orderId() : null;
```

- size + 1 조회 → 초과하면 hasNext = true, 마지막 항목의 orderId가 nextCursor
- 상품 목록 조회와 동일한 패턴

---

## 11. DB 접근 최적화 (옵션 2개 주문 기준)

| 순서 | 쿼리 | 설명 |
|------|------|------|
| 1 | SELECT address + user | 배송지 + 유저 fetch join |
| 2 | INSERT orders | 주문 생성 |
| 3 | SELECT option + product + store FOR UPDATE | 옵션1 비관적 락 |
| 4 | SELECT option + product + store FOR UPDATE | 옵션2 비관적 락 |
| 5 | UPDATE products_options | 옵션1 재고 차감 (dirty checking) |
| 6 | UPDATE products_options | 옵션2 재고 차감 (dirty checking) |
| 7 | INSERT order_items (배치) | 주문 항목 저장 |

총 7번. 최적화 포인트:
- User 별도 조회 제거 — Address fetch join에 User 포함
- Product/Store 프록시 초기화 방지 — findByIdWithLock() fetch join
- 주문 항목 배치 INSERT — saveAll()

---

## 12. 코드 한줄한줄 예상 면접 질문

---

### OrderController.createOrder()

```java
@PostMapping
public ResponseEntity<OrderResponse> createOrder(
        @Valid @RequestBody OrderCreateRequest request,
        HttpServletRequest httpRequest) {
    Long userId = SessionUtils.getUserId(httpRequest);
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(orderService.createOrder(userId, request));
}
```

**Q: 왜 POST인가? PUT이 아닌 이유는?**
> POST는 새 리소스를 생성할 때 사용한다. PUT은 특정 리소스를 교체하는 멱등 연산인데, 주문 생성은 호출할 때마다 새로운 주문이 만들어지므로 멱등하지 않다.

**Q: @Valid가 없으면 어떻게 되는가?**
> DTO의 @NotNull, @NotEmpty, @Positive 검증이 동작하지 않아 잘못된 값이 서비스까지 도달한다. 예를 들어 quantity가 -1이어도 통과되어 재고가 증가하는 버그가 발생할 수 있다.

**Q: @Valid와 @Validated의 차이는?**
> @Valid는 Jakarta Bean Validation 표준으로 중첩 객체 검증에 사용한다. @Validated는 Spring 고유 어노테이션으로 그룹 검증을 지원한다. 여기서는 OrderItemRequest 중첩 검증이 필요해서 @Valid를 사용했다.

**Q: 왜 HttpServletRequest를 서비스에 넘기지 않는가?**
> 서비스 레이어가 HTTP에 의존하면 테스트할 때 HttpServletRequest를 목킹해야 한다. userId만 추출해서 넘기면 서비스는 HTTP를 몰라도 되고 단위 테스트가 간단해진다. 계층 간 관심사 분리 원칙이다.

**Q: 왜 201 CREATED인가? 200 OK로 하면 안 되는가?**
> 새 리소스(Order)가 생성되었음을 명시적으로 알리기 위해 HTTP 표준을 따랐다. 200도 동작은 하지만 클라이언트가 "새 리소스가 생성되었다"는 의미를 구분할 수 없다.

**Q: @RequestBody 없이 보내면?**
> Spring이 요청 본문을 파싱하지 못해 HttpMessageNotReadableException이 발생한다. GlobalExceptionHandler에서 400 Bad Request로 처리된다.

---

### OrderService.createOrder()

```java
@ExecutionTimeCheck(threshold = 3000)
public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
    OrderResponse orderResponse = orderTransactionService.createOrder(userId, request);
    orderTimeoutManager.registerTimeout(orderResponse.orderId());
    return orderResponse;
}
```

**Q: @ExecutionTimeCheck는 어떻게 동작하는가?**
> AOP로 구현되어 있다. 메서드 실행 시간을 측정하여 threshold(3초)를 초과하면 WARN 로그를 남긴다. 주문 생성에 비관적 락 대기가 포함되어 있어 동시 트래픽이 몰리면 느려질 수 있는데, 이를 조기에 감지하기 위해 적용했다.

**Q: 이 클래스에 @Transactional이 없는 이유는?**
> OrderTransactionService에 @Transactional이 있다. 이 클래스에 @Transactional을 걸면 Redis 타이머 등록과 추후 결제 API 호출까지 트랜잭션 안에 들어가서 DB 커넥션을 불필요하게 점유한다. 트랜잭션 범위를 DB 작업만으로 한정하기 위해 의도적으로 제외했다.

**Q: 왜 OrderService와 OrderTransactionService를 분리했는가?**
> 두 가지 이유다. 첫째, 같은 클래스에서 @Transactional 메서드를 호출하면 Spring 프록시가 적용되지 않아 트랜잭션이 안 걸린다. 둘째, 토스 결제 API를 트랜잭션 밖에서 호출해야 하는데, 분리하지 않으면 외부 API 호출(수초) 동안 커넥션이 잠긴다.

**Q: @Transactional 프록시가 왜 내부 호출에서 안 되는가?**
> Spring은 빈 등록 시 프록시 객체를 만든다. 외부에서 호출하면 프록시를 거쳐 트랜잭션이 시작되지만, 클래스 내부에서 this.method()를 호출하면 프록시가 아닌 실제 객체를 호출하므로 트랜잭션이 적용되지 않는다.

**Q: registerTimeout()을 트랜잭션 안에서 하면 어떤 문제가 있는가?**
> 트랜잭션이 롤백되면 주문은 DB에 생성되지 않았는데 Redis에만 타이머가 등록된다. 30분 뒤 스케줄러가 이 주문을 취소하려 하면 ORDER_NOT_FOUND 예외가 발생한다. 트랜잭션 커밋이 확정된 후에 등록해야 한다.

**Q: 트랜잭션 커밋 후 registerTimeout()이 실패하면?**
> 주문은 PENDING 상태로 DB에 남아있고 Redis 타이머는 없다. 이 경우 DB 보정 스케줄러가 1시간 뒤 PENDING 상태 + created_at이 30분 이상 지난 주문을 발견하여 자동 취소한다. 이중 안전망 설계다.

**Q: TransactionTemplate은 왜 안 썼는가?**
> @Transactional이 더 깔끔하고 테스트하기 쉽다. TransactionTemplate은 BCrypt 해싱처럼 트랜잭션 밖에서 할 작업이 메서드 중간에 있을 때 사용한다. 주문은 서비스 분리로 해결했기 때문에 불필요하다.

---

### OrderTransactionService.createOrder()

```java
@Transactional
public OrderResponse createOrder(Long userId, OrderCreateRequest request) {
    Address address = userService.findAddressByOwner(userId, request.addressId());
    User user = address.getUser();
```

**Q: 왜 UserRepository를 직접 쓰지 않고 UserService를 호출하는가?**
> DDD에서 도메인 간 접근은 Service 레이어를 통해야 한다. OrderTransactionService가 AddressRepository를 직접 참조하면 도메인 경계가 무너진다. 추후 User 도메인의 내부 구현이 변경되어도 주문 도메인은 영향받지 않는다.

**Q: findAddressByOwner()에서 어떤 검증을 하는가?**
> 두 가지다. 배송지 존재 여부(ADDRESS_NOT_FOUND)와 본인 배송지인지 소유권 검증(UNAUTHORIZED_ORDER_ACCESS). 다른 사람의 addressId를 넣으면 403이 반환된다.

**Q: address.getUser()에서 추가 쿼리가 발생하지 않는 이유는?**
> findAddressByOwner() 내부에서 `findByIdWithUser()`를 사용하여 Address와 User를 fetch join으로 한 쿼리에 가져온다. 이미 영속성 컨텍스트에 User가 로딩되어 있으므로 프록시 초기화가 발생하지 않는다.

**Q: fetch join을 안 쓰면 어떻게 되는가?**
> Address를 조회한 뒤 address.getUser()를 호출할 때 User를 조회하는 SELECT가 추가로 발생한다. 단 1건이지만, 대용량 트래픽에서는 모든 주문 요청마다 불필요한 쿼리가 실행되는 것이므로 제거해야 한다.

**Q: 다른 사람의 배송지로 주문하면?**
> findAddressByOwner()에서 배송지의 userId와 세션의 userId를 비교한다. 불일치하면 UNAUTHORIZED_ORDER_ACCESS 예외가 발생하여 트랜잭션이 롤백된다.

```java
    Order savedOrder = orderRepository.save(Order.create(user, address));
```

**Q: Order.create()에서 pgOrderId는 어떻게 생성하는가?**
> `ORD-날짜-UUID앞8자리` 형식이다. 예: `ORD-20260327-a3f8b2c1`. 날짜가 포함되어 언제 생성된 주문인지 바로 파악 가능하고, UUID 8자리로 중복을 방지한다. 토스페이먼츠 연동 시 PG사 주문번호로 활용된다.

**Q: 왜 팩토리 메서드 패턴을 사용하는가?**
> 생성자를 직접 호출하면 orderStatus를 PENDING으로 설정하는 것을 빠뜨릴 수 있다. 팩토리 메서드로 생성 규칙을 캡슐화하면 어디서 생성해도 항상 올바른 초기 상태가 보장된다. 생성자는 NoArgsConstructor(PROTECTED)로 외부 접근을 막았다.

**Q: save() 시점에 INSERT가 즉시 실행되는가?**
> GenerationType.IDENTITY 전략이므로 save() 호출 시 INSERT가 즉시 실행되어 DB에서 orderId를 받아온다. SEQUENCE 전략과 달리 배치 INSERT가 불가한 단점이 있지만, MySQL에서는 IDENTITY가 표준이다.

**Q: save()를 먼저 하는 이유는?**
> OrderItem 생성 시 Order 엔티티의 참조(FK)가 필요하다. save()로 INSERT하여 orderId가 확정된 후에 OrderItem을 생성해야 FK 제약조건을 만족한다.

```java
    List<OrderItem> savedItems = decreaseStockAndCreateItems(savedOrder, request.items());
```

**Q: 재고 차감과 주문 항목 생성을 왜 하나의 메서드로 묶었는가?**
> 비관적 락으로 옵션을 조회하면 Product와 Store가 fetch join으로 함께 로딩된다. 이 데이터를 재고 차감 직후 바로 사용하여 OrderItem을 생성한다. 분리하면 같은 데이터를 두 번 조회해야 한다.

```java
    int totalAmount = savedItems.stream()
            .mapToInt(OrderItem::getTotalPrice)
            .sum();

    List<OrderItemResponse> itemResponses = savedItems.stream()
            .map(OrderItemResponse::from)
            .toList();

    return OrderResponse.from(savedOrder, itemResponses, totalAmount, savedOrder.calculateFinalAmount(totalAmount));
```

**Q: 왜 totalAmount를 DTO가 아닌 엔티티에서 계산하는가?**
> DTO는 외부 전달용이지 비즈니스 계산의 입력이 되면 안 된다. DTO 구조가 바뀌면 금액 계산이 깨진다. 엔티티의 getTotalPrice()로 계산하면 DTO 변경과 무관하게 비즈니스 로직이 보호된다.

**Q: calculateFinalAmount()를 서비스에서 안 하고 엔티티에서 하는 이유는?**
> 최종 금액 계산은 Order의 discountAmount, usedPoint를 사용하는 도메인 로직이다. 서비스에 두면 호출하는 모든 곳에서 중복되고, 할인 규칙이 변경될 때 여러 곳을 수정해야 한다. 엔티티에 캡슐화하면 한 곳만 수정하면 된다.

**Q: stream을 두 번 돌리는데 하나로 합칠 수 없는가?**
> 합칠 수 있지만 가독성이 떨어진다. totalAmount 계산과 DTO 변환은 관심사가 다르다. stream 2회의 비용은 메모리 내 연산이라 DB 쿼리 1회보다 수만 배 빠르므로 성능 영향이 없다.

---

### decreaseStockAndCreateItems()

```java
private List<OrderItem> decreaseStockAndCreateItems(Order order, List<OrderItemRequest> itemRequests) {
    List<OrderItemRequest> merged = mergeAndSort(itemRequests);
```

**Q: mergeAndSort()가 하는 일 두 가지는?**
> 첫째, 같은 optionId의 수량을 합산한다. optionId=1(2개) + optionId=1(3개) → optionId=1(5개). 둘째, optionId를 오름차순 정렬한다. 모든 트랜잭션이 같은 순서로 락을 획득하여 deadlock을 방지한다.

**Q: deadlock이 왜 발생하는가?**
> 스레드 A가 옵션1 → 옵션2 순으로 락을 잡고, 스레드 B가 옵션2 → 옵션1 순으로 잡으면 서로가 서로를 기다리는 순환 대기에 빠진다. 모든 트랜잭션이 optionId 오름차순으로 통일하면 순환이 발생하지 않는다.

**Q: 왜 TreeMap인가? HashMap + sort로 하면 안 되는가?**
> 가능하지만 두 단계가 필요하다. TreeMap은 삽입 시점에 자동 정렬되므로 정렬과 합산을 하나의 자료구조로 동시에 처리한다. 코드가 간결하고 의도가 명확하다.

```java
    List<OrderItem> items = new ArrayList<>(merged.size());
    for (OrderItemRequest request : merged) {
        ProductOption option = productService.decreaseStockWithLock(
                request.productOptionId(), request.quantity()
        );
```

**Q: decreaseStockWithLock()에 @Transactional이 없는데 트랜잭션은 어떻게 적용되는가?**
> 호출자인 createOrder()에 @Transactional이 있다. @Transactional이 없는 메서드가 트랜잭션 내에서 호출되면 기존 트랜잭션에 자연스럽게 참여한다. 별도 트랜잭션이 필요 없으므로 선언하지 않았다.

**Q: 비관적 락(SELECT FOR UPDATE)이 실제로 어떻게 동작하는가?**
> DB에서 해당 행에 배타적 락을 건다. 다른 트랜잭션이 같은 행을 읽으려면 현재 트랜잭션이 커밋/롤백될 때까지 대기한다. 트랜잭션이 끝나면 락이 해제되고 다음 대기자가 최신 데이터를 읽는다.

**Q: 100명이 동시에 같은 옵션을 주문하면?**
> 1명이 락을 획득하고 나머지 99명은 대기한다. 트랜잭션 하나당 ~10ms라면 100번째 사용자는 ~1초를 대기한다. 현재 패션 이커머스 특성상 같은 옵션에 100명이 동시에 몰리는 경우는 타임세일이 아니면 드물다.

**Q: 1000명이면 어떻게 대응하는가?**
> ~10초 대기가 발생하여 사용자 이탈이 생긴다. Redis DECR 원자적 연산으로 전환하면 락 없이 ~1ms로 처리 가능하다. @DistributedLock AOP를 미리 준비해두었으므로 전환 비용이 크지 않다.

**Q: 재고가 부족하면 어떻게 되는가?**
> ProductOption.decreaseStock()에서 stockQuantity < quantity이면 INSUFFICIENT_STOCK 예외를 던진다. @Transactional이 런타임 예외를 감지하여 전체 트랜잭션을 롤백한다. 이미 차감한 다른 옵션의 재고도 복구된다.

**Q: 왜 ProductService를 통해 재고를 차감하는가? ProductOptionRepository를 직접 쓰면 안 되는가?**
> DDD에서 도메인 간 접근은 Service 레이어를 통해야 한다. OrderTransactionService가 ProductOptionRepository를 직접 참조하면 주문 도메인이 상품 도메인의 내부 구현에 의존하게 된다.

```java
        items.add(OrderItem.create(
                option, order, request.quantity(),
                option.getProductPrice(), null
        ));
    }
```

**Q: option.getProductPrice()에서 추가 쿼리가 발생하지 않는 이유는?**
> findByIdWithLock()에서 `JOIN FETCH po.product p JOIN FETCH p.store`로 Product와 Store를 한 쿼리에 가져왔다. getProductPrice()는 이미 로딩된 Product의 price를 반환할 뿐이다.

**Q: getProductPrice()는 왜 위임 메서드로 만들었는가?**
> `option.getProduct().getPrice()`는 디미터 법칙 위반이다. ProductOption 내부 구조(Product가 price를 갖고 있다는 사실)를 외부에 노출한다. 위임 메서드를 두면 Product 구조가 변경되어도 ProductOption만 수정하면 된다.

**Q: 마지막 인자 null은 무엇인가?**
> UserCoupon이다. 현재 쿠폰 기능이 미구현이라 null을 전달한다. 추후 쿠폰 적용 시 해당 주문 항목에 사용된 쿠폰 정보가 들어간다.

```java
    return orderItemRepository.saveAll(items);
}
```

**Q: saveAll()과 for문에서 save()를 반복하는 것의 차이는?**
> saveAll()은 내부적으로 배치로 처리하여 DB 라운드트립을 줄인다. for문에서 save()를 반복하면 항목마다 INSERT가 실행되어 N번 라운드트립이 발생한다.

**Q: IDENTITY 전략인데 배치 INSERT가 되는가?**
> Hibernate에서 IDENTITY 전략은 즉시 INSERT로 ID를 받아야 하므로 JDBC 레벨 배치는 안 된다. 하지만 saveAll()을 사용하면 Hibernate가 내부적으로 한 번에 처리하므로 개별 save()보다는 효율적이다.

---

### mergeAndSort()

```java
private List<OrderItemRequest> mergeAndSort(List<OrderItemRequest> itemRequests) {
    Map<Long, Integer> merged = new TreeMap<>();
    for (OrderItemRequest request : itemRequests) {
        merged.merge(request.productOptionId(), request.quantity(), Integer::sum);
    }
```

**Q: merge() 메서드는 어떻게 동작하는가?**
> 키가 없으면 값을 삽입하고, 키가 이미 있으면 세 번째 인자(BiFunction)로 기존 값과 새 값을 합산한다. Integer::sum은 (기존수량, 새수량) → 기존수량 + 새수량이다.

**Q: 같은 옵션을 중복 주문하면 어떻게 되는가?**
> optionId=1(수량2), optionId=1(수량3)이 들어오면 merge()가 수량을 합산하여 optionId=1(수량5)가 된다. 락을 1번만 획득하고 재고도 5개를 한번에 차감한다.

```java
    List<OrderItemRequest> result = new ArrayList<>(merged.size());
    for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
        result.add(new OrderItemRequest(entry.getKey(), entry.getValue()));
    }
    return result;
}
```

**Q: TreeMap의 entrySet()은 정렬이 보장되는가?**
> 보장된다. TreeMap은 Red-Black Tree로 키를 정렬 상태로 유지하며, entrySet()도 키 오름차순으로 순회한다.

**Q: new OrderItemRequest()로 새 객체를 만드는 이유는?**
> 원본 요청은 optionId별로 분리되어 있을 수 있다. 합산된 수량으로 새 DTO를 만들어야 이후 로직에서 정확한 수량으로 처리된다. record는 불변이므로 기존 객체를 수정할 수 없다.

---

### cancelOrder()

```java
@Transactional
public void cancelOrder(Long orderId) {
    Order order = orderRepository.findByIdWithLock(orderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
```

**Q: 왜 findById()가 아니라 findByIdWithLock()인가?**
> 동시 취소를 방지하기 위해서다. 락 없이 두 스레드가 동시에 PENDING을 읽으면 둘 다 cancel()에 성공하여 재고가 이중 복구된다. 비관적 락으로 한 스레드만 처리하고, 두 번째는 CANCELLED 상태를 읽어 예외가 발생한다.

**Q: 이 메서드를 호출하는 곳은 어디인가?**
> 세 곳이다. Redis ZSET 스케줄러(30분 타임아웃), DB 보정 스케줄러(누락 주문), 추후 결제 실패 시 OrderService. 어디서 호출하든 비관적 락으로 동시성이 보장된다.

```java
    order.cancel();
```

**Q: 상태 검증을 서비스에서 하지 않고 엔티티에서 하는 이유는?**
> 취소 가능한 상태인지 판단하는 건 주문 도메인의 핵심 규칙이다. 서비스에 두면 cancelOrder()를 호출하는 모든 곳에서 if문을 반복해야 하고, 하나라도 빠지면 잘못된 상태에서 취소가 될 수 있다. 엔티티에 캡슐화하면 규칙이 한 곳에서 보장된다.

**Q: PAID 상태에서 취소하면?**
> cancel()에서 orderStatus != PENDING이면 INVALID_ORDER_STATUS 예외를 던진다. 결제 완료된 주문은 별도의 환불 프로세스가 필요하며, 단순 cancel()로는 처리할 수 없다.

```java
    List<OrderItem> items = orderItemRepository.findByOrderIdWithDetails(orderId);
    for (OrderItem item : items) {
        productService.increaseStock(
                item.getProductOption().getProductOptionId(),
                item.getQuantity()
        );
    }
}
```

**Q: 왜 findByOrderOrderId()가 아닌 findByOrderIdWithDetails()인가?**
> findByOrderOrderId()는 fetch join이 없다. for문에서 item.getProductOption()을 호출할 때마다 LAZY 로딩으로 SELECT가 발생하여 N+1 문제가 생긴다. findByOrderIdWithDetails()는 ProductOption + Product + Store를 한 쿼리로 가져온다.

**Q: increaseStock()에서도 비관적 락을 사용하는 이유는?**
> 동시에 다른 사용자가 같은 옵션을 주문(decreaseStockWithLock) 중일 수 있다. 락 없이 읽은 값에 증가시키면 주문의 차감이 유실되는 Lost Update가 발생한다. 재고를 변경하는 모든 경로에서 동일한 락 전략을 사용해야 정합성이 보장된다.

**Q: 재고 복구 중 하나가 실패하면?**
> @Transactional이므로 전체가 롤백된다. order.cancel()로 변경한 상태도 롤백되어 주문은 여전히 PENDING이다. 스케줄러가 다음 주기에 다시 취소를 시도한다.

**Q: 결제 실패 시 어떻게 되는가?**
> OrderService에서 orderTransactionService.cancelOrder()를 호출한다. 별도 트랜잭션으로 주문 취소 + 재고 복구를 한 뒤, orderTimeoutManager.removeTimeout()으로 Redis 타이머도 제거한다.

---

### 스케줄러

**Q: 왜 스케줄러가 두 개인가?**
> Redis ZSET 스케줄러는 정상적인 30분 타임아웃을 처리한다. DB 보정 스케줄러는 Redis 장애로 타이머가 등록되지 못한 주문을 잡는 안전망이다. 트랜잭션 커밋 후 Redis 등록 전에 서버가 죽으면 타이머가 누락되는데, 이걸 DB 스케줄러가 보정한다.

**Q: Lua 스크립트를 왜 사용하는가?**
> ZRANGEBYSCORE로 조회한 뒤 개별 ZREM으로 제거하면, 조회와 제거 사이에 다른 서버가 같은 데이터를 읽을 수 있다. Lua 스크립트는 Redis에서 원자적으로 실행되어 조회+제거가 하나의 연산으로 처리된다.

**Q: 1초마다 폴링하면 부하가 있지 않는가?**
> Redis ZRANGEBYSCORE는 메모리 조회로 만료 주문이 없으면 ~0.1ms다. DB 스캔이 아니라 커넥션 풀에 영향이 없다. CPU 점유율 0.01% 수준이므로 실질적 부하가 없다.

**Q: 왜 Spring Scheduler 대신 Redis ZSET인가?**
> DB 폴링은 매번 `SELECT * FROM orders WHERE status = 'PENDING'`을 실행한다. 주문이 300만 건이면 인덱스를 타더라도 부하가 크다. Redis ZSET은 메모리 기반 O(log N + M)으로 DB 부하 없이 처리한다. 정밀도도 DB 폴링(분 단위) 대비 1초 이내로 정확하다.

**Q: 보정 스케줄러에 LIMIT을 건 이유는?**
> Redis가 장시간 장애이면 PENDING 주문이 수천~수만 건 누적될 수 있다. LIMIT 없이 한번에 조회하면 OOM이 발생할 수 있다. 100건씩 배치로 처리하여 메모리를 보호한다.

---

### 동시성 테스트

**Q: 동시 주문 테스트는 어떻게 했는가?**
> ExecutorService와 CountDownLatch로 10개 스레드가 동시에 주문하는 테스트를 작성했다. 재고 10개에 10명 동시 주문 시 정확히 10명 성공 재고 0, 재고 5개에 10명 시 5명 성공 5명 실패 재고 0을 확인했다.

---

### DB 접근 최적화

**Q: 주문 생성 시 총 몇 번의 쿼리가 실행되는가? (옵션 2개 기준)**
> 7번이다.

| 순서 | 쿼리 | 설명 |
|------|------|------|
| 1 | SELECT address + user | 배송지 + 유저 fetch join |
| 2 | INSERT orders | 주문 생성 |
| 3 | SELECT option + product + store FOR UPDATE | 옵션1 비관적 락 |
| 4 | SELECT option + product + store FOR UPDATE | 옵션2 비관적 락 |
| 5 | UPDATE products_options | 옵션1 재고 차감 |
| 6 | UPDATE products_options | 옵션2 재고 차감 |
| 7 | INSERT order_items (배치) | 주문 항목 저장 |

**Q: 더 줄일 수 있는가?**
> 옵션별 비관적 락 SELECT는 행 단위 락이라 IN 절로 묶을 수 없다. 순서대로 락을 획득해야 deadlock이 방지되므로 옵션 수만큼 SELECT가 필요하다. UPDATE도 dirty checking으로 각각 실행된다. 현재 구조에서는 7쿼리가 최적이다.

---

### 보안

**Q: 다른 사람의 주문을 조회할 수 있는가?**
> 세션의 userId와 주문의 userId를 비교하여 본인 주문만 접근 가능하다. 다른 orderId를 넣으면 UNAUTHORIZED_ORDER_ACCESS가 반환된다.

**Q: size에 큰 값을 보내면?**
> MAX_PAGE_SIZE = 100으로 제한한다. size=999999를 보내도 100건만 반환하여 OOM을 방지한다.
