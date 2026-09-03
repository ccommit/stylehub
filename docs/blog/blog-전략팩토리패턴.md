# 전략 + 팩토리 패턴으로 다중 PG사 결제 시스템 설계하기

## 들어가며

이커머스 프로젝트에서 토스페이먼츠 결제를 연동하면서, "카카오페이나 네이버페이가 추가되면 어떻게 하지?"라는 고민이 생겼다. PG사마다 승인 API 호출 방식이 다른데, 매번 if-else로 분기하면 코드가 금방 복잡해진다. 전략 패턴과 팩토리 패턴을 조합하여 확장 가능한 결제 시스템을 설계한 과정을 정리한다.

## 문제: PG사가 추가될 때마다 코드 수정

가장 단순한 방식은 if-else 분기다.

```java
public void confirmPayment(String pgType, String paymentKey, String orderId, Integer amount) {
    if ("TOSS".equals(pgType)) {
        // 토스 승인 API 호출
    } else if ("KAKAO".equals(pgType)) {
        // 카카오 승인 API 호출
    } else if ("NAVER".equals(pgType)) {
        // 네이버 승인 API 호출
    }
    // PG사가 추가될 때마다 여기를 수정해야 한다
}
```

이 방식의 문제:
- PG사가 추가될 때마다 기존 코드를 수정해야 한다 (OCP 위반)
- 하나의 메서드에 모든 PG사 로직이 섞인다
- 테스트할 때 특정 PG사만 테스트하기 어렵다

## 해결 1단계: 전략 패턴 — 공통 인터페이스 정의

PG사별 결제 승인 로직을 인터페이스로 추상화한다.

```java
public interface PaymentClient {

    void confirmPayment(String paymentKey, String orderId, Integer amount);

    // 팩토리에서 구현체를 식별하기 위한 PG사 타입
    String getType();
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

        restTemplate.postForEntity(
                tossProperties.getConfirmUrl(),
                new HttpEntity<>(body, headers),
                String.class
        );
    }

    @Override
    public String getType() {
        return "TOSS";
    }
}
```

카카오페이를 추가하려면 같은 인터페이스를 구현하면 된다:

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

## 해결 2단계: 팩토리 패턴 — 런타임에 구현체 선택

실제 이커머스에서는 사용자가 결제 수단을 선택한다. "이 주문은 토스로 결제, 저 주문은 카카오로 결제"가 가능해야 한다. 팩토리가 pgType에 따라 적절한 구현체를 반환한다.

```java
@Component
public class PaymentClientFactory {

    private final Map<String, PaymentClient> clients;

    // Spring이 PaymentClient 구현체를 모두 주입 → getType()으로 Map에 등록
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

핵심은 생성자에서 `List<PaymentClient>`를 받는 부분이다. Spring이 `PaymentClient` 인터페이스를 구현한 모든 Bean을 자동으로 수집해서 주입한다. 새로운 구현체에 `@Component`만 붙이면 팩토리가 자동으로 인식한다.

## 사용하는 쪽: PaymentService

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
        // ...
    }
}
```

PaymentService는 `PaymentClient` 인터페이스만 알고, 토스인지 카카오인지 관심이 없다. Payment 엔티티에 저장된 `pgType`에 따라 팩토리가 구현체를 선택한다.

## 왜 전략만, 팩토리만으로는 안 되는가

### 전략 패턴만 사용한 경우

```java
// 주입 시점에 하나로 고정
private final PaymentClient paymentClient;
```

우리 서비스가 토스만 쓴다면 충분하다. 하지만 사용자가 주문마다 다른 PG사를 선택하는 상황에서는 런타임에 구현체를 바꿀 수 없다.

### 팩토리 패턴만 사용한 경우

```java
public PaymentClient getClient(String pgType) {
    if ("TOSS".equals(pgType)) return new TossPaymentClient(...);
    if ("KAKAO".equals(pgType)) return new KakaoPaymentClient(...);
}
```

PG사가 추가될 때마다 팩토리의 if문을 수정해야 한다. OCP를 위반한다.

### 전략 + 팩토리 조합

인터페이스(전략)로 공통 계약을 정의하고, Spring의 자동 수집 + Map(팩토리)으로 구현체를 등록한다. 새 PG사 추가 시 **구현체 클래스만 만들면 끝**이다. 팩토리도, 서비스도 수정할 필요 없다.

## PG사 추가 시 체크리스트

1. `XxxPaymentClient implements PaymentClient` 클래스 생성
2. `@Component` 붙이기
3. `getType()`에서 PG사 이름 반환 (예: "KAKAO")
4. `confirmPayment()`에 해당 PG사 승인 API 호출 로직 작성
5. 끝 — 기존 코드 수정 0줄

## 정리

- 전략 패턴으로 PG사별 결제 로직을 인터페이스 뒤에 캡슐화한다
- 팩토리 패턴으로 런타임에 pgType에 따라 구현체를 선택한다
- Spring의 List 주입으로 구현체를 자동 수집하여 if-else 없는 팩토리를 만든다
- 새 PG사 추가 시 기존 코드 수정 없이 구현체만 추가하면 된다 (OCP 준수)
