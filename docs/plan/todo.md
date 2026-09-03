
# TODO

## 보안 (CRITICAL)

- [ ] application.properties에 노출된 시크릿키를 환경변수로 분리 (Google OAuth, 토스, DB 비밀번호)
- [ ] application-dev.properties / application-prod.properties 프로필 분리
- [ ] CORS 설정 추가 (WebConfig)
- [ ] 세션 저장소를 Redis로 전환 (현재 인메모리 — 서버 재시작 시 세션 유실)

## 예외 처리 통일 (HIGH)

- [ ] UserValidator — IllegalArgumentException → BusinessException 전환
- [ ] UserService.login() — IllegalArgumentException → BusinessException 전환
- [ ] OAuthService — IllegalArgumentException → BusinessException 전환
- [ ] GoogleOAuthClient — RestClientException에 커스텀 에러코드 적용

## 대용량 트래픽 (성능)

- [ ] 비관적 락 상태에서 TPS 측정 (현재 구현)
- [ ] 분산 락(Redis)으로 전환 후 TPS 비교
- [ ] 분산 락 leaseTime 튜닝 (1초 → 최적값 도출)
- [ ] 분산 락 폴링 방식 개선 — 50ms 고정 sleep → 지수 백오프(exponential backoff)
- [ ] 분산 락 실패 시 INTERNAL_SERVER_ERROR → 전용 에러코드(LOCK_ACQUISITION_FAILED) 변경
- [ ] DB 인덱스 추가 — User.email, Order.userId, ProductOption.productId 등 자주 조회되는 컬럼
- [ ] 주문 목록 조회 시 총액 배치 조회 최적화 (QueryDSL 단일 쿼리로 통합 검토)
- [ ] HikariCP 커넥션 풀 사이즈 설정 (기본 10개 → 트래픽에 맞게 조정)
- [ ] Redis 커넥션 풀 설정
- [ ] 캐시 stampede 분산 방어 검토 — `ProductService` 의 `@Cacheable(sync = true)` 는 단일 JVM 락이라 인스턴스 N대 환경에선 cache miss 시 N개 동시 DB 쿼리 발생. 다중 인스턴스 부하 테스트로 영향 실측 후 필요 시 Redisson `RReadWriteLock` 또는 다단(local + Redis) 캐시로 전환

## 확장성

- [ ] 재고 차감 Redis DECR 원자적 연산 전환 (비관적 락 → Redis 원자적 연산)
- [ ] 블로그 글 작성: "비관적 락에서 분산 락으로 전환한 이유와 성능 비교"

## 결제

- [ ] Mock 모드 적용 (토스 API 없이 Postman만으로 테스트 가능)
- [ ] 토스 승인 성공 + DB 실패 시 보상 처리 (토스 취소 API 호출)
- [ ] Payment.cancel() 멱등성 보장 — 중복 호출 시 cancelAmount 누적 방지
- [ ] OrderController TODO 주석 제거 (PaymentController로 이관 완료)
- [ ] Order.pgOrderId TODO 주석 제거 (이미 활용 중)
- [ ] 부분 취소 구현 — OrderItem에 canceledQuantity 필드 추가, 항목 단위 취소 + 재고 복구

## 미구현 기능

- [ ] 쿠폰 유효성 검증 + 할인 금액 계산
- [ ] 쿠폰 사용 처리 (UserCoupon 상태 변경)
- [ ] 포인트 잔액 확인 + 차감
- [ ] 포인트 차감 처리 (User.pointBalance 차감 + PointHistory 기록)
- [ ] 주문 취소 시 유저 메일 발송
- [ ] 주소(Address) CRUD API — 현재 엔티티만 존재, API 없음
- [ ] Order.calculateFinalAmount() 음수 방지 검증 추가

## 경량 헥사고날 — Port(인터페이스) 도입

도메인 간 직접 참조를 제거하고, 소비자 도메인에 Port(인터페이스)를 정의하여 간접 의존으로 전환한다.
헥사고날 전체 도입 없이 핵심 원칙(Port/Adapter)만 가볍게 차용한다.

- [ ] **OrderService → UserService 분리** — `AddressFinder` 포트를 order 도메인에 정의, UserService가 구현. placeOrder()에서 userService.findAddressByOwner() 호출을 AddressFinder로 교체
- [ ] **OrderService → ProductService 분리** — `StockManager` 포트를 order 도메인에 정의, ProductService가 구현. placeOrder()의 decreaseStockWithLock(), cancelOrder()의 increaseStock() 호출을 StockManager로 교체
- [ ] **OrderService → PaymentService 분리** — `PaymentCreator` 포트를 order 도메인에 정의, PaymentService가 구현. 주문 생성 시 결제 대기 건 생성을 이벤트 방식에서 Port 직접 호출 방식으로 전환
- [ ] **ProductService → StoreService 분리** — `StoreOwnerValidator` 포트를 product 도메인에 정의, StoreService가 구현. registerProduct(), getMyStoreProducts(), updateStock()에서 storeService 호출을 StoreOwnerValidator로 교체
- [ ] **PointRewardService → UserRepository 분리** — `PointTargetFinder` 포트를 point 도메인에 정의, UserService가 구현. rewardLoginPoint()에서 userRepository.findById() 호출을 PointTargetFinder로 교체

## 코드 품질 (가독성 / 유지보수)

- [ ] DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE 상수 중복 제거 — OrderService, ProductService에 동일 상수 존재, 공통 상수로 추출
- [ ] resolvePageSize() 로직 중복 제거 — PaginationUtil로 추출
- [ ] LoginEventListener 트랜잭션 페이즈 재검토 — BEFORE_COMMIT → AFTER_COMMIT으로 통일 (포인트 적립 실패가 로그인을 롤백시키면 안 됨)
- [ ] Request DTO 유효성 검증 강화 — ProductCreateRequest에 price > 0, StockUpdateRequest에 stockQuantity >= 0, PaymentCancelRequest에 cancelAmount > 0
- [ ] ProductOption에 BaseEntity 상속 누락 — createdAt/updatedAt 감사 추적 불가
- [ ] 소유권 검증 패턴 통일 — findOrderByOwner(), findAddressByOwner(), findApprovedStoreByOwner() 네이밍/구조 일관성 검토
- [ ] GoogleOAuthClient에서 RestClient 인라인 생성 → Bean 주입으로 변경 (테스트 용이성)
- [ ] TransactionTemplate vs @Transactional 사용 기준 정리 — 현재 서비스마다 혼용

## 테스트

- [ ] PaymentService 승인/취소 단위 테스트
- [ ] 위변조 검증 테스트 (금액 불일치 시 에러 반환)
- [ ] CancelPolicy 테스트 (배송 상태별 취소 가능 여부)
- [ ] 부분 취소 잔액 관리 테스트
- [ ] OrderService placeOrder 통합 테스트
- [ ] Controller 엔드포인트 테스트 (MockMvc)

## 운영

- [ ] Redis 세션 + 사용자 상태 캐싱
- [ ] Swagger API 문서 자동화
- [ ] 로깅 레벨 설정 (dev: DEBUG, prod: INFO)
- [ ] Actuator 헬스체크 / 메트릭 설정
- [ ] 트랜잭션 로깅 AOP 추가 (시작/커밋/롤백 시점 기록, 성능테스트 후 병목 지점 파악 시 적용)

## 포트폴리오 어필 (블로그 글)

- [ ] 비관적 락 vs 분산 락 성능 비교 글
- [ ] 성능테스트 결과 분석 글 (nGrinder or k6)
- [ ] 쿠폰 선착순 발급 동시성 제어 글 (구현 후)
