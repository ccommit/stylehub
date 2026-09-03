# 전략 + 팩토리 패턴으로 다중 PG사 결제 시스템 설계 — 변경 비용을 PG 수와 분리하기

## 왜 첫 PG 연동 시점부터 다중 PG 구조를 설계했나

이커머스 결제는 **PG사 추가가 시간 문제이지 가능성의 문제가 아닌 영역**이다. 토스, 카카오페이, 네이버페이, 페이코 — 어떤 PG가 언제 추가될지는 사업적 의사결정에 달려 있고, 개발자가 통제할 수 있는 변수가 아니다. 따라서 첫 PG 연동을 작성하는 시점에 던져야 하는 질문은 "토스를 어떻게 호출할 것인가"가 아니라 다음과 같다.

> **"PG 수가 N개로 늘어나도 결제 도메인의 변경 비용이 N에 비례해 늘어나지 않게 만들 수 있는가?"**

이 질문을 미루면, 두 번째 PG 추가 시점에 결제 도메인 전체가 if-else의 미궁으로 변한다. 한 PG 로직 수정이 다른 PG 로직의 회귀 가능성을 동반하고, 새 PG 추가는 기존 PG 전부에 대한 회귀 테스트 부담으로 변환된다. 즉 **변경 비용 = 누적된 PG 수**라는 등식이 성립한다.

이 글은 첫 PG(토스) 연동 시점부터 **변경 비용을 PG 수와 분리하는 구조(전략 + 팩토리)** 를 의도적으로 설계한 작업의 기록이다. 단순히 "디자인 패턴을 적용했다"가 아니라, **각 패턴이 어떤 변경 압력에 대응하는지**를 의식하고 조합한 결정이다.

---

## 안티패턴 분석 — if-else 분기의 누적 비용

먼저 구조 없이 풀었을 때의 모습을 정확히 짚는다.

```java
public void confirmPayment(String pgType, String paymentKey, String orderId, Integer amount) {
    if ("TOSS".equals(pgType)) {
        // 토스: Base64 인코딩 시크릿키, POST /v1/payments/confirm
    } else if ("KAKAO".equals(pgType)) {
        // 카카오: API 키 헤더, POST /v1/payment/approve
    } else if ("NAVER".equals(pgType)) {
        // 네이버: 또 다른 방식...
    }
}
```

이 구조의 누적 비용을 변경 시나리오별로 정리하면 다음과 같다.

| 변경 시나리오 | 영향 받는 위치 | 회귀 위험 |
|------------|--------------|---------|
| 새 PG 추가 | 같은 메서드 (OCP 위반) | 기존 PG 로직 전체 |
| 토스 API 변경 | 같은 메서드 | 다른 PG 로직 |
| 단위 테스트 | 메서드 분리 불가 | 특정 PG만 격리해서 테스트 어려움 |
| 책임 | 한 메서드에 모든 PG의 인증·API 형식이 동시 거주 (SRP 위반) | — |

이를 **PG 수와 무관하게 변경 비용을 고정**시키는 구조로 바꾸는 것이 다음 절(전략 + 팩토리)의 목적이다.

---

## 두 패턴의 역할 분담 — 각 패턴이 풀어야 하는 변경 축

전략 패턴과 팩토리 패턴을 함께 쓰는 이유는 **두 패턴이 서로 다른 변경 축을 담당**하기 때문이다.

| 패턴 | 담당하는 변경 축 |
|------|---------------|
| 전략 (인터페이스 뒤 캡슐화) | "PG별 호출 방식이 서로 다르다" |
| 팩토리 (런타임 선택) | "주문마다 사용할 PG가 다르다" |

두 패턴 중 하나만 적용하면 다음 절(왜 두 패턴을 조합하는가)에서 보듯 한쪽 변경 축은 풀리지만 다른 쪽이 남는다. **둘을 조합해야 변경 비용이 PG 수와 분리**된다.

### 전략 — PG별 호출 방식의 차이를 인터페이스 뒤로 격리

PG사별 결제 승인 로직을 인터페이스 뒤에 캡슐화한다. 호출자(PaymentService)는 **인터페이스 계약만 알고 구체 구현은 모른다**.

```java
public interface PaymentClient {

    void confirmPayment(String paymentKey, String orderId, Integer amount);

    String getType(); // "TOSS", "KAKAO" 등
}
```

토스 구현체:

```java
@Component
public class TossPaymentClient implements PaymentClient {

    private final TossPaymentProperties tossProperties;
    private final RestTemplate restTemplate;

    @Override
    public void confirmPayment(String paymentKey, String orderId, Integer amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Basic " + encodeSecretKey());

        Map<String, Object> body = Map.of(
                "paymentKey", paymentKey,
                "orderId", orderId,
                "amount", amount
        );

        try {
            restTemplate.postForEntity(
                    tossProperties.getConfirmUrl(),
                    new HttpEntity<>(body, headers),
                    String.class
            );
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.PAYMENT_APPROVAL_FAILED);
        }
    }

    @Override
    public String getType() {
        return "TOSS";
    }

    private String encodeSecretKey() {
        return Base64.getEncoder().encodeToString(
                (tossProperties.getSecretKey() + ":").getBytes(StandardCharsets.UTF_8)
        );
    }
}
```

카카오페이 추가 시:

```java
@Component
public class KakaoPaymentClient implements PaymentClient {

    @Override
    public void confirmPayment(String paymentKey, String orderId, Integer amount) {
        // 카카오 승인 API 호출
    }

    @Override
    public String getType() {
        return "KAKAO";
    }
}
```

각 PG의 인증 방식·API 형식이 아무리 달라도 `confirmPayment()` 안에 캡슐화되어 있으므로, **호출자에서 본 차이는 0**이다. 이로써 첫 번째 변경 축(PG별 호출 방식 차이)은 해결된다.

### 팩토리 — 런타임에 PG 구현체 선택

전략 패턴만으로는 두 번째 변경 축(주문마다 다른 PG 선택)을 풀 수 없다. Spring 주입 시점에 구현체가 고정되기 때문이다. 사용자가 결제 수단을 선택하는 이커머스 환경에서는 **런타임에 구현체를 동적으로 바꿔야** 한다.

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

핵심은 생성자의 `List<PaymentClient>`다. Spring이 `PaymentClient`를 구현한 모든 Bean을 자동으로 수집해서 주입한다. **`@Component`가 붙은 구현체는 등록 코드 없이 자동으로 팩토리에 추가**된다 — 이것이 진정한 OCP를 만족시키는 결정적 메커니즘이다.

---

## 왜 두 패턴을 조합하는가 — 단독 적용의 한계

각 패턴을 단독으로 쓸 때의 한계를 보면, 조합이 왜 필수인지 자명해진다.

### 전략만 쓰는 경우 — 정적 주입의 한계

```java
private final PaymentClient paymentClient; // 하나로 고정
```

주입 시점에 하나의 구현체로 고정되므로 **첫 번째 변경 축(PG 호출 방식 차이)은 풀리지만 두 번째 축(런타임 선택)이 남는다**. 단일 PG 환경이라면 충분하지만, 다중 PG 이커머스에서는 부족하다.

### 팩토리만 쓰는 경우 — OCP 위반이 위치만 옮겨감

```java
public PaymentClient getClient(String pgType) {
    if ("TOSS".equals(pgType)) return new TossPaymentClient(...);
    if ("KAKAO".equals(pgType)) return new KakaoPaymentClient(...);
    // PG 추가 → if문 추가 → OCP 위반
}
```

팩토리가 구현체를 직접 알면, PG 추가 시 팩토리 코드 수정이 필요하다. **OCP 위반의 위치가 PaymentService에서 팩토리로 이동했을 뿐, 본질적 비용은 그대로**다.

### 조합의 결과 — 변경 비용을 PG 수와 분리

전략(인터페이스 계약) + 팩토리(Spring 자동 수집)의 조합 효과:

| 변경 시나리오 | 수정해야 할 기존 코드 |
|------------|-----------------|
| 새 PG 추가 | **0줄** (새 구현체 클래스 1개 추가) |
| 토스 API 형식 변경 | TossPaymentClient 1개만 수정 |
| 결제 수단 동적 선택 로직 변경 | PaymentService의 호출 부분만 |

이 분리가 **변경 비용을 PG 수와 분리**한다. 첫 PG든 100번째 PG든, 추가 비용이 동일하다.

---

## 서비스에서의 사용

```java
@Service
public class PaymentService {

    private final PaymentClientFactory paymentClientFactory;

    @Transactional
    public PaymentResponse approvePayment(String paymentKey, String orderId, Integer amount) {
        Payment payment = paymentRepository.findByOrderPgOrderId(orderId)
                .orElseThrow(...);

        // Payment 엔티티의 pgType으로 구현체 선택
        paymentClientFactory.getClient(payment.getPgType())
                .confirmPayment(paymentKey, orderId, amount);

        payment.approve(amount);
        payment.getOrder().markPaid();

        return PaymentResponse.from(payment);
    }
}
```

PaymentService는 토스인지 카카오인지 모른다. Payment 엔티티에 저장된 `pgType`에 따라 팩토리가 알아서 구현체를 선택한다.

Payment 엔티티:

```java
@Entity
public class Payment {
    // ...
    @Column(name = "pg_type", nullable = false, length = 20)
    private String pgType; // "TOSS", "KAKAO" 등
}
```

주문 생성 시 사용자가 선택한 결제 수단에 따라 pgType이 결정된다.

---

## PG사 추가 시 해야 할 일

1. `XxxPaymentClient implements PaymentClient` 클래스 생성
2. `@Component` 붙이기
3. `getType()`에서 PG사 이름 반환
4. `confirmPayment()`에 해당 PG사 승인 API 호출 로직 작성
5. 끝

수정하는 파일: **0개**. 추가하는 파일: **1개**.

---

## 테스트에서의 이점

```java
// 테스트용 Mock 구현체
public class MockPaymentClient implements PaymentClient {

    @Override
    public void confirmPayment(String paymentKey, String orderId, Integer amount) {
        // 아무것도 안 함 — 외부 API 호출 없이 테스트 가능
    }

    @Override
    public String getType() {
        return "MOCK";
    }
}
```

테스트 시 실제 토스 API를 호출하지 않고 MockPaymentClient를 사용할 수 있다. **인터페이스 추상화의 부수적 가치** — 테스트 격리가 자연스럽게 따라온다. 의도하지 않았지만, 변경 비용 분리를 위해 도입한 구조가 테스트 가능성까지 함께 끌어올린다.

---

## 정리 — "패턴은 변경 압력에 대한 응답이다"

이 작업의 핵심 결정은 **각 패턴이 어떤 변경 축을 담당하는지**를 명시하고 조합한 것이다.

| 패턴 | 담당 변경 축 | 단독 적용 시 한계 |
|------|-----------|---------------|
| 전략 (인터페이스 캡슐화) | PG별 호출 방식 차이 | 런타임 선택 불가 |
| 팩토리 (자동 수집) | 런타임 PG 선택 | OCP 위반의 위치만 이동 |
| **조합** | **양쪽 축 모두** | — (변경 비용이 PG 수와 분리) |

이 표가 의미하는 본질:

> **디자인 패턴은 "잘 만든 코드"의 도구가 아니라 "특정 변경 압력에 대한 응답"이다.** 어떤 패턴을 적용할지는 "어떤 변경 압력을 풀어야 하는가"에서 출발해야 한다. 변경 압력이 없는데 패턴을 도입하는 것은 과잉 설계이고, 변경 압력이 있는데 패턴을 도입하지 않는 것은 누적 비용을 미루는 것이다.

이번 PG 연동에서는 **명시적인 변경 압력(PG 수의 증가, 사용자별 결제 수단 선택)** 이 처음부터 존재했고, 그 압력에 정확히 대응하는 두 패턴을 조합했다. 결과적으로 **PG 추가 비용이 0줄**로 수렴했고, 이는 첫 PG 연동 시점의 작은 추가 설계 비용으로 회수되는 가치다.
