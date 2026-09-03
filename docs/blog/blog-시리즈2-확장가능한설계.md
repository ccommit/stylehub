# [시리즈 2] 확장 가능한 결제 시스템 설계 — 변경 압력에 대비한 구조

## 왜 첫 PG사 연동 시점에 확장 구조를 함께 설계했나

이커머스에서 PG사가 하나로 끝나는 사례는 거의 없다. 토스, 카카오페이, 네이버페이, 페이코, 그리고 직불·계좌이체까지 — 결제 수단의 추가는 시간 문제이지 가능성의 문제가 아니다. 따라서 PG 연동 코드를 작성하는 시점에 던져야 하는 질문은 "토스를 어떻게 호출할 것인가"가 아니라 다음과 같다.

> **"PG사가 N개로 늘어났을 때, 기존 코드를 수정하지 않고 새 PG사를 추가할 수 있는가?"**

이 질문을 첫 연동 시점에 미루면, 두 번째 PG사를 추가하는 순간 결제 도메인 전체가 if-else의 미궁이 된다. 그리고 if-else가 한 번 자라기 시작하면 **새 PG사 하나가 기존 PG 로직 전부를 회귀 테스트해야 하는 부담**으로 변환된다. 이는 곧 결제 도메인 변경의 "비용 = 누적된 PG 수"라는 등식을 만든다.

이 글은 PG 연동의 시작점부터 **변경 압력에 대비한 구조(전략 + 팩토리 + 도메인 분리)** 를 의도적으로 설계한 작업의 기록이다. 추가로, 주문과 결제 도메인의 경계를 어떻게 정의했는지도 같이 다룬다 — 두 결정이 같은 원리(**변경 사유가 다른 것은 다른 위치에 둔다**)에서 나오기 때문이다.

---

## 1. 안티패턴 분석 — if-else 분기의 누적 비용

먼저 구조 없이 풀었을 때의 모습을 명확히 한다.

```java
public void confirmPayment(String pgType, ...) {
    if ("TOSS".equals(pgType)) {
        // 토스: Base64 인코딩 시크릿키, POST /v1/payments/confirm
    } else if ("KAKAO".equals(pgType)) {
        // 카카오: API 키 헤더, POST /v1/payment/approve
    } else if ("NAVER".equals(pgType)) {
        // 네이버: 또 다른 방식...
    }
}
```

PG사마다 인증 방식, API 형식이 다른데, 하나의 메서드에 전부 들어간다. 이 구조의 누적 비용을 정리하면 다음과 같다.

| 비용 항목 | 누적 양상 |
|----------|---------|
| 변경 시 영향 범위 | PG 추가/수정마다 같은 메서드를 열어야 함 — OCP 위반 |
| 회귀 테스트 부담 | 한 PG 로직 수정이 다른 PG 로직 회귀 가능성 동반 |
| 단일 책임 위반 | 한 메서드가 모든 PG의 인증·API 형식을 동시에 담당 |
| 단위 테스트 격리 | 특정 PG만 테스트하기 어려워짐 |

즉 if-else 분기는 **PG 수에 비례해 변경 비용이 선형으로 누적**되는 구조다. 이를 **PG 수와 무관하게 변경 비용을 고정**시키는 구조로 바꾸는 것이 다음 두 절(전략 패턴 + 팩토리 패턴)의 목적이다.

---

## 2. 전략 패턴 — 변경 축의 첫 분리

PG사별 결제 로직을 인터페이스 뒤에 숨긴다.

```java
public interface PaymentClient {
    void confirmPayment(String paymentKey, String orderId, Integer amount);
    void cancelPayment(String paymentKey, String cancelReason, Integer cancelAmount);
    String getType();  // "TOSS", "KAKAO" 등
}
```

토스 구현체:

```java
@Component
public class TossPaymentClient implements PaymentClient {

    @Override
    public void confirmPayment(String paymentKey, String orderId, Integer amount) {
        // 토스 고유의 인증 방식과 API 호출
        headers.set("Authorization", "Basic " + encodeSecretKey());
        restTemplate.postForEntity(tossProperties.getConfirmUrl(), ...);
    }

    @Override
    public String getType() {
        return "TOSS";
    }
}
```

카카오를 추가하려면 같은 인터페이스를 구현한다. PaymentService는 `PaymentClient` 인터페이스만 알고, 토스인지 카카오인지 모른다. 이로써 **PG 호출 로직이 PaymentService로부터 캡슐화**되어 한 차원의 변경 축이 분리된다.

### 전략 패턴의 한계 — 정적 주입은 런타임 선택을 지원하지 않는다

```java
private final PaymentClient paymentClient;  // 하나로 고정
```

전략 패턴만 적용하면 Spring 주입 시점에 구현체가 결정된다. 그러나 실제 이커머스에서는 **사용자가 주문마다 다른 결제 수단을 선택**하므로, 런타임에 구현체를 동적으로 바꿔야 한다. 이 동적 선택을 위해 한 단계 더 — 팩토리 패턴 — 가 필요하다.

---

## 3. 팩토리 패턴 — 런타임에 선택

```java
@Component
public class PaymentClientFactory {

    private final Map<String, PaymentClient> clients;

    public PaymentClientFactory(List<PaymentClient> paymentClients) {
        this.clients = paymentClients.stream()
                .collect(Collectors.toMap(PaymentClient::getType, Function.identity()));
    }

    public PaymentClient getClient(String pgType) {
        PaymentClient client = clients.get(pgType);
        if (client == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return client;
    }
}
```

핵심은 생성자의 `List<PaymentClient>`다. Spring이 `PaymentClient`를 구현한 모든 Bean을 자동으로 수집한다. `@Component`가 붙은 구현체는 **등록 코드 없이 팩토리에 추가**된다. 이것이 OCP를 만족시키는 결정적 메커니즘이다.

### 팩토리의 두 가지 구현 — 직접 매핑 vs Spring 자동 수집

팩토리 자체는 두 가지 방식으로 구현할 수 있다.

```java
// 후보 A: if-else로 직접 분기 (OCP 위반)
public PaymentClient getClient(String pgType) {
    if ("TOSS".equals(pgType)) return new TossPaymentClient(...);
    if ("KAKAO".equals(pgType)) return new KakaoPaymentClient(...);
}

// 후보 B: Spring DI로 모든 구현체 자동 수집 (채택)
public PaymentClientFactory(List<PaymentClient> paymentClients) { ... }
```

후보 A는 팩토리 자체에 PG가 추가될 때마다 수정이 필요하므로 OCP 위반의 위치가 단지 PaymentService → 팩토리로 옮겨졌을 뿐이다. **후보 B만이 진정한 OCP를 만족**한다 — 새 PG는 `@Component`만 붙이면 자동 수집되어 팩토리 수정이 필요 없다.

### 조합의 결과 — "PG 추가의 변경 비용 = 0줄"

전략(인터페이스 계약) + 팩토리(자동 수집) 조합의 효과:

| 변경 시나리오 | 수정해야 할 기존 코드 |
|------------|------------------|
| 새 PG사 추가 | **0줄** (새 구현체 클래스 1개만 추가) |
| 토스 API 형식 변경 | TossPaymentClient 1개만 수정 |
| 검증 규칙 추가 | PaymentService(또는 PaymentValidator)만 수정 |

이 분리가 의미하는 본질은 **변경 압력이 영향을 미치는 영역을 좁힌다**는 것이다. 구조 없이 두면 변경 압력이 시스템 전체로 퍼지고, 구조를 두면 변경 압력이 해당 구현체 안에 격리된다.

---

## 4. 서비스에서의 사용

```java
@Service
public class PaymentService {

    private final PaymentClientFactory paymentClientFactory;

    public PaymentResponse approvePayment(String paymentKey, String orderId, Integer amount) {
        Payment payment = findPaymentByOrderId(orderId);

        paymentValidator.validateApprovable(payment);
        paymentValidator.validateAmount(payment, amount);

        // PG사 타입에 따라 구현체 자동 선택
        paymentClientFactory.getClient("TOSS").confirmPayment(paymentKey, orderId, amount);

        payment.approve(amount);
        payment.getOrder().markPaid();

        return PaymentResponse.from(payment);
    }
}
```

PaymentService는 토스를 모른다. `paymentClientFactory.getClient()`이 반환하는 게 토스든 카카오든 상관없이 `confirmPayment()`만 호출한다.

### PG사 추가 시 해야 할 일

1. `KakaoPaymentClient implements PaymentClient` 클래스 생성
2. `@Component` 붙이기
3. `getType()`에서 `"KAKAO"` 반환
4. 끝 — 기존 코드 수정 0줄

---

## 5. 주문과 결제 도메인의 경계 — 변경 사유에 따른 분리

### 분리의 근거 — 같은 시스템, 다른 변경 압력

주문과 결제는 사용자 관점에서는 한 흐름이지만, 시스템 관점에서는 **수명 주기, 트랜잭션 경계, 외부 의존성, 정책 소유권**이 모두 다른 두 컨텍스트다. [시리즈 1](blog-시리즈1-결제안전하게.md)에서 트랜잭션 분리의 근거를 다뤘듯, 이 분리는 단순한 패키지 정리가 아니라 **각 도메인의 변경 압력을 격리하는 구조적 결정**이다.

### 의존 방향 — 호출의 무게에 따른 비대칭 결합

```
OrderService → PaymentRepository (READY 생성만)
PaymentService → OrderService (cancelOrder 호출)
```

Order가 Payment를 완전히 모르는 건 아니다. 주문 생성 시 Payment READY를 만든다. 하지만 Payment의 승인, 취소 같은 비즈니스 로직은 모른다.

반대로 Payment는 Order의 `cancelOrder()`를 호출한다. 결제 실패 시 주문을 취소하고 재고를 복구해야 하기 때문이다. 이 방향은 보상 트랜잭션의 본질적 요구다 — "결제가 실패하면 주문을 취소한다"는 도메인 흐름이 그대로 의존 방향으로 나타난다.

### 순환 의존을 푸는 세 가지 후보 비교

두 도메인이 양방향 호출을 필요로 하면 즉시 순환 의존이 발생한다. 이를 푸는 후보는 셋이다.

| 후보 | 방식 | 트레이드오프 |
|------|------|-------------|
| A. 양방향 이벤트 분리 | 모든 도메인 간 호출을 이벤트화 | 단순 INSERT까지 이벤트화하면 추적성 저하 |
| **B. Service → Repository 직접 호출 (단방향만)** | Order는 PaymentRepository만 사용 | 도메인 경계 일부 양보, 단순성 확보 |
| C. Facade Service | 별도 OrderPaymentFacade가 양쪽 호출 | 책임 분산이 흐려지고 새로운 신을 만듦 |

**후보 B를 채택**한 근거는 **결합 강도를 호출의 무게에 비례시키는 것**이다.

- Order → Payment 방향의 호출은 "Payment를 READY 상태로 INSERT" 하나뿐이며, 비즈니스 규칙이 없는 단순 영속화다. 이 한 호출에 이벤트와 비동기 처리를 도입하는 것은 트레이드오프가 맞지 않다
- 반대 방향(Payment → Order의 보상 처리)은 비즈니스 규칙이 있으므로 **OrderService를 거친다**

```java
// OrderService — Payment 비즈니스 로직 모름, 단순 영속화만
paymentRepository.save(Payment.create(savedOrder, ...));

// PaymentService — Order 비즈니스 로직(취소/재고 복구) 호출
orderService.cancelOrder(order.getOrderId());
```

원칙: **양 방향에 동일한 추상화를 강요하지 않고, 각 호출의 무게에 비례한 결합 강도를 적용한다.** 모든 도메인 간 호출을 이벤트로 푸는 것은 안전하지만 추적이 어렵고, 모든 호출을 Service로 푸는 것은 깔끔하지만 결합이 강해진다. **무게에 따른 차등 적용**이 실용적 답이다.

---

## 6. 정책 소유권 — "데이터의 위치"가 아니라 "변경 압력의 출처"

### CancelPolicy를 Payment 도메인에 둔 결정

환불 정책은 "배송 중이면 취소 불가, 배송 완료 후 7일 지나면 환불 불가" 같은 규칙을 검증한다. 이 정책의 소속을 결정할 때 가장 자주 빠지는 함정은 **데이터의 위치를 정책의 위치와 동일시**하는 것이다.

| 기준 | Order에 두기 | Payment에 두기 |
|------|------------|--------------|
| 데이터 접근 편의성 | ★★★ (배송 상태가 Order의 필드) | ★★ |
| 호출 시점의 의미 | "주문이 자기 상태를 검증" | "결제 취소가 가능한지 결제가 판단" |
| **변경 압력의 원천** | **배송 정책 변경 시** | **환불 정책 변경 시** |
| 호출자 | PaymentService | PaymentService |

**결정적 근거**: 환불 기간이 7일에서 14일로 바뀌는 변경이 발생할 때, 수정 압력의 원천은 **결제 도메인의 비즈니스 규칙**에 있다. 만약 이 정책이 Order에 있다면 결제 정책 변경이 주문 도메인의 코드를 수정시키는 **역방향 결합**이 생긴다.

원칙: **데이터는 읽기 전용으로 공유하되, 판단의 주체는 변경 압력을 받는 도메인에 둔다.**

```
payment/
├── policy/
│   ├── CancelPolicy.java       — 배송 상태별 환불 가능 여부
│   └── PaymentValidator.java   — 결제 상태/금액 검증
```

### PaymentValidator로 검증을 분리한 결정

`approvePayment()` 같은 핵심 메서드는 자칫 7가지 책임을 동시에 가질 수 있다.

```
1. Payment 조회
2. 상태 검증
3. 금액 검증
4. 토스 API 호출
5. Payment 상태 변경
6. Order 상태 변경
7. 타이머 제거
```

이 7가지를 한 메서드 안에 두면 **각 책임의 변경 사유가 다른데도 같은 위치에서 변경**된다 — SRP 위반의 전형적 패턴이다. 그래서 다음과 같이 책임을 변경 사유별로 분리했다.

- **검증 규칙 변경** → PaymentValidator
- **PG사 추가/변경** → PaymentClientFactory
- **환불 정책 변경** → CancelPolicy
- **상태 전이 규칙 변경** → 엔티티(Payment.approve 등)

서비스(`PaymentService`)는 이 분리된 컴포넌트들을 호출하는 **흐름 조율(orchestration)** 만 담당한다.

```java
public PaymentResponse approvePayment(String paymentKey, String orderId, Integer amount) {
    Payment payment = findPaymentByOrderId(orderId);

    paymentValidator.validateApprovable(payment);       // 검증
    paymentValidator.validateAmount(payment, amount);    // 검증

    paymentClientFactory.getClient("TOSS")              // PG사 호출
            .confirmPayment(paymentKey, orderId, amount);

    payment.approve(amount);                            // 엔티티 자체 상태 변경
    payment.getOrder().markPaid();
    orderPaymentTimeout.removeTimeout(...);

    return PaymentResponse.from(payment);
}
```

각 클래스의 책임이 명확해졌다.

| 클래스 | 책임 |
|---|---|
| PaymentService | 흐름 조율 |
| PaymentValidator | 상태/금액 검증 |
| CancelPolicy | 환불 가능 여부 판단 |
| PaymentClientFactory | PG사 구현체 선택 |
| Payment 엔티티 | 자체 상태 변경 |

---

## 7. 에러코드에서도 경계가 보인다

```
OR001 — 존재하지 않는 주문
OR002 — 현재 상태에서는 처리 불가
PM001 — 존재하지 않는 결제
PM002 — 결제 금액 불일치
PM007 — 배송 중에는 취소 불가
```

접두사만으로 어느 도메인에서 터진 에러인지 파악할 수 있다. 운영 환경에서 장애 대응 속도에 영향을 준다.

---

## 현재 도메인 경계

```
Order 도메인
├── OrderController — 주문 생성/조회
├── OrderService — 주문 생성, 취소, 조회 (흐름 조율)
├── Order, OrderItem — 엔티티 (상태 변경은 엔티티가 담당)
├── OrderCreatedEvent — 이벤트로 도메인 간 결합 최소화
└── OrderPaymentTimeout — Redis 타임아웃

Payment 도메인
├── PaymentController — 결제 승인/취소/실패
├── PaymentService — 승인, 취소, 실패 처리 (흐름 조율)
├── Payment — 엔티티
├── PaymentClient (interface) → TossPaymentClient — 전략 + 팩토리
├── PaymentValidator — 상태/금액 검증
└── CancelPolicy — 환불 정책
```

---

## 정리 — "확장 가능성은 변경 사유의 분리에서 나온다"

이 시스템의 모든 구조적 결정은 단일 원리로 수렴한다.

> **변경 사유가 같은 것들은 모으고, 변경 사유가 다른 것들은 나눈다.**

이 원리를 각 결정에 적용하면 다음과 같다.

| 변경 시나리오 | 영향 받는 위치 | 영향 받지 않는 위치 |
|------------|--------------|------------------|
| PG사 추가 | 새 PaymentClient 구현체 1개 | PaymentService, 다른 PG, 도메인 |
| 환불 기간 변경 (7일→14일) | CancelPolicy 상수 1개 | Order 엔티티, 검증 로직, 서비스 |
| 검증 규칙 추가 | PaymentValidator | 서비스 흐름, 도메인 모델 |
| 토스 API 변경 | TossPaymentClient | 다른 PG, 서비스, 검증 |
| 결제 상태 전이 규칙 변경 | Payment 엔티티 | 서비스, 검증, PG 클라이언트 |

이 표가 의미하는 본질은 "각 변경의 영향 범위가 좁다"는 것이다. 변경의 영향 범위가 좁으면 회귀 테스트의 범위도 좁아지고, 새 기능 추가의 비용도 누적되지 않는다.

확장 가능한 설계의 진짜 가치는 **"미래의 모든 가능성에 대비한다"** 가 아니라 **"실제 일어날 변경의 영향 범위를 최소화한다"** 이다. 이 차이를 의식하면 과잉 설계를 피하면서도 실용적 확장성을 확보할 수 있다.
