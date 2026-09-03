# 결제 금액 위변조 검증 — 클라이언트 경유 데이터를 신뢰하지 않는다는 원칙

## 왜 이 검증을 "공식문서 가이드"가 아닌 "보안 모델의 핵심"으로 다뤘나

토스페이먼츠 공식문서는 서버에서 금액 검증을 하라고 명시한다. 이 가이드를 단순히 따르는 것과, **검증의 본질이 무엇이고 빠뜨리면 어떤 위험이 발생하는지**를 이해한 뒤 따르는 것은 다른 차원의 작업이다. 후자만이 같은 원리를 다른 보안 결정에도 일관되게 적용할 수 있다.

이 글은 결제 위변조 검증을 다음 일반 원리의 적용 사례로 다룬다.

> **클라이언트를 경유하는 모든 데이터는 조작 가능하다. 따라서 보안에 영향을 주는 값은 서버가 직접 쓴 불변 기록과의 비교를 거쳐야만 신뢰할 수 있다.**

이 원리는 결제뿐 아니라 다음 영역에도 동일하게 적용된다.

| 영역 | 원리 적용 |
|------|---------|
| 결제 금액 | DB requestedAmount vs 클라이언트 amount |
| 사용자 권한 | DB role vs 토큰 claim (둘 다 검증) |
| 주문 소유권 | DB userId vs 요청 userId (인증 토큰 기준) |
| 쿠폰 적용 | DB 쿠폰 정보 vs 클라이언트 할인 금액 |

이 글은 그 일반 원리의 결제 도메인 적용을 정리한 기록이다.

## 결제 흐름에서 위변조가 가능한 지점

토스페이먼츠 결제 흐름은 이렇다.

```
1. 프론트에서 orderId + amount로 결제창 진입
2. 사용자가 결제 수단 인증 완료
3. 토스가 successUrl로 리다이렉트 (paymentKey, orderId, amount 전달)
4. 우리 서버가 토스에 승인 API 호출
5. 결제 완료
```

취약점이 발생하는 정확한 지점은 **3번**이다. successUrl로 리다이렉트될 때 전달되는 amount는 **클라이언트(사용자 브라우저)를 경유**한다. 즉 토스 → 우리 서버로 직접 전달되는 것이 아니라, 토스가 사용자 브라우저로 보낸 리다이렉트 URL을 사용자 브라우저가 우리 서버로 다시 호출하는 구조다. 이 사이의 모든 단계에서 데이터는 조작 가능하다.

이 구조적 취약점은 **토스의 보안 결함이 아니라 OAuth 스타일 리다이렉트 플로우의 본질적 특성**이다. 따라서 어떤 PG로 바꿔도 동일한 검증이 필요하다.

## 공격 시나리오

```
1. 10만원짜리 상품을 주문한다
2. 결제창에서 인증을 완료한다
3. 리다이렉트 URL의 amount=100000을 amount=1000으로 변경
4. 서버가 토스에 1000원으로 승인 요청
5. 1000원만 결제되고 10만원짜리 상품을 받는다
```

서버가 클라이언트에서 넘어온 amount를 그대로 믿으면 이런 공격이 가능하다.

## 해결: 서버에서 금액 비교

주문 생성 시 결제 금액을 미리 DB에 저장해두고, 승인 요청 전에 비교한다.

### 1. 주문 생성 시 — 금액을 서버에 저장

```java
// OrderService.placeOrder()
int totalAmount = savedItems.stream()
        .mapToInt(OrderItem::getTotalPrice)
        .sum();
int finalAmount = savedOrder.calculateFinalAmount(totalAmount);

// Payment 엔티티에 requestedAmount로 저장
paymentRepository.save(Payment.create(
        savedOrder, "", "주문 결제",
        finalAmount, totalAmount, finalAmount, "TOSS"
));
```

이 시점에서 `requestedAmount = finalAmount`가 DB에 저장된다. 이 값은 클라이언트가 건드릴 수 없다.

### 2. 승인 요청 전 — DB 금액과 토스 전달 금액 비교

```java
// PaymentService.approvePayment()
Payment payment = paymentRepository.findByOrderPgOrderId(orderId)
        .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

// 위변조 검증 — DB 저장 금액과 토스 전달 금액 비교
if (!payment.getRequestedAmount().equals(amount)) {
    throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
}

// 일치하면 토스 승인 API 호출
paymentClientFactory.getClient(payment.getPgType()).confirmPayment(paymentKey, orderId, amount);
```

DB에 저장된 `requestedAmount`와 토스에서 리다이렉트로 넘어온 `amount`가 다르면 PAYMENT_AMOUNT_MISMATCH 예외를 던지고 결제를 중단한다.

## 흔한 오해 — "토스 서버가 검증해주지 않나?"

이 시점에서 자주 나오는 반론은 "토스에서 넘어온 amount니까 토스가 이미 검증한 것 아닌가?"다. 이 오해를 정확히 깨야 검증의 필연성이 이해된다.

| 시스템 | 알고 있는 것 | 알지 못하는 것 |
|--------|-----------|-------------|
| 우리 서버 | 주문의 정확한 금액 (DB requestedAmount) | 사용자가 결제창에서 인증한 금액 |
| 토스 | 프론트에서 요청받은 금액 = 사용자 인증 금액 | 우리 서버의 주문 금액 |

**핵심**: 토스는 "프론트에서 요청한 금액"을 받아 사용자 인증을 진행할 뿐, 그 금액이 우리 서버의 주문 금액과 일치하는지를 알 방법이 없다. 그리고 프론트에서 토스로 보내는 금액 자체가 클라이언트 코드이므로 조작 가능하다.

따라서 **금액 검증의 책임은 양쪽 시스템 어디에도 위임할 수 없으며**, 우리 서버가 두 값(DB 저장 금액 vs 토스 전달 금액)을 직접 비교해야만 한다. 이는 PG 종류와 무관한 **OAuth 스타일 결제 플로우의 본질적 보안 모델**이다.

## Payment 엔티티 설계

```java
@Entity
public class Payment {
    private Integer requestedAmount;  // 주문 생성 시 서버가 계산한 금액
    private Integer totalAmount;      // 총 상품 금액
    private Integer approvedAmount;   // 토스 승인 완료된 금액
    private PaymentStatus status;     // READY → DONE or ABORTED
}
```

- `requestedAmount`: 위변조 검증의 기준이 되는 금액. 주문 생성 시 서버가 계산하여 저장.
- `approvedAmount`: 토스 승인 완료 후 실제 결제된 금액. 승인 전에는 0.
- 두 값이 일치해야 정상 결제.

## 정리 — "신뢰의 출처"를 의식적으로 결정하는 보안 설계

이 검증의 본질은 단일 if문 한 줄이 아니라 **신뢰의 출처(source of truth)를 어디에 둘 것인가**의 결정이다.

| 신뢰 후보 | 평가 |
|---------|------|
| 클라이언트 amount | ❌ 조작 가능 (개발자 도구로 변경) |
| 토스 전달 amount | ❌ 토스는 클라이언트 요청을 그대로 전달할 뿐 |
| **DB requestedAmount** | ✅ 서버가 직접 계산해 영속화한 불변 기록 |

이 패턴이 일반화되면 다음 원리에 도달한다.

> **분산된 시스템 사이의 정합성·보안은 "서버가 직접 쓴 불변 기록"으로만 이을 수 있다. 클라이언트나 외부 시스템에서 들어오는 값은 검증 대상이지 신뢰 대상이 아니다.**

이 원리는 [도메인 분리 글](blog-도메인분리고민-v2.md)의 두 트랜잭션 연결, [트랜잭션 범위 글](blog-트랜잭션범위고민.md)의 분리 트랜잭션 정합성, 그리고 이 글의 위변조 검증에 모두 동일하게 적용된다. 결제 시스템의 안전성은 **이 원리를 매번 의식적으로 적용했는가** 의 누적이다 — 어떤 한 지점에서 빠뜨리면 그 지점이 곧 취약점이 된다.
