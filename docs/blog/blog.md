# BCrypt 커넥션 풀 고갈 문제 해결 — TransactionTemplate으로 처리량 34배 개선

## 왜 이 문제를 처음부터 인지하고 설계했나

StyleHub는 패션 이커머스 플랫폼이고, 인증은 모든 사용자가 통과하는 트래픽의 첫 관문이다. 회원가입·로그인 API를 설계할 때 가장 먼저 던져야 하는 질문은 이거다.

> **"이 API의 처리량 한계가 시스템 전체의 처리량 한계가 되지 않는가?"**

BCrypt는 의도적으로 느리게 설계된 해싱 알고리즘이다(~100ms / 회). 그리고 Spring의 `@Transactional`은 메서드 진입 시점에 DB 커넥션을 즉시 획득한다. 이 두 사실이 합쳐지면 다음 등식이 성립한다.

```
@Transactional 메서드 안에서 BCrypt 실행
= 커넥션 점유 시간 ≥ BCrypt 실행 시간 (~100ms)
= 커넥션 풀 회전율 ≤ 풀 크기 / 100ms
= HikariCP 풀 10개 기준 → 시스템 처리량 상한 ≈ 100 req/s
```

이 등식의 결정적 함정은 **풀이 모든 API에 의해 공유된다는 점**이다. 로그인 트래픽이 풀을 점유하면 상품 조회·주문·결제까지 모두 멈춘다 — 즉 **BCrypt의 비용이 인증 도메인 안에 격리되지 않고 시스템 전체 가용성을 결정**한다.

이 글은 이 문제를 설계 단계에서 인지하고, `TransactionTemplate`으로 트랜잭션 범위를 DB 작업으로만 한정해 **장애 격리와 처리량을 동시에 확보**한 의사결정의 기록이다.

### BCrypt는 왜 느린가

BCrypt는 의도적으로 느리게 설계된 해싱 알고리즘이다.

| cost | 해싱 시간 | 용도 |
|---|---|---|
| 10 | ~80-100ms | 일반적인 서비스 (본 프로젝트 채택) |
| 12 | ~300-400ms | 높은 보안 요구 |
| 14 | ~1,000ms+ | 매우 높은 보안 요구 |

이 느린 연산이 회원가입(해싱)과 로그인(검증) 양쪽에서 실행된다.

### 비용의 정량화 — 문제의 크기를 측정으로 확정

설계 단계의 가설(BCrypt가 풀을 점유하면 시스템 처리량이 떨어진다)을 정량화하면 다음과 같다. HikariCP 커넥션 풀의 기본 최대 크기는 10개이고, `@Transactional`은 메서드가 끝날 때까지 커넥션을 점유한다.

```
커넥션 1개 점유 시간 = DB 조회 (~3ms) + BCrypt (~100ms) = ~103ms
커넥션 1개 초당 처리량 = 1000 / 103 ≈ 9.7건
HikariCP 풀 10개 기준  = 9.7 × 10 = ~97 req/s
```

**초당 97건.** 동시 요청이 100건만 넘어도 커넥션 대기가 시작되고, 타임아웃이 발생한다. 그리고 더 결정적인 사실은 — **이 커넥션 풀이 인증 전용이 아니라 시스템 전체 공유 자원**이라는 점이다.

```
로그인 폭주 → 커넥션 풀 고갈 → 상품 조회 타임아웃 → 주문 API 타임아웃 → 전체 장애
```

이 패턴이 의미하는 것은 명확하다 — **인증 도메인의 비용 모델이 시스템 전체의 가용성 모델을 결정**한다. 인증을 격리하지 않으면 인증 트래픽 한 번에 시스템이 무너진다. 따라서 이 문제는 "로그인 성능 최적화"가 아니라 **"장애 격리 아키텍처"의 영역**이고, 그 관점에서 풀어야 한다.

---

## 본문

### 1. 변경 전 코드 — @Transactional 방식

#### 회원가입

```java
@Transactional
public UserSignUpResponse signUp(UserSignUpRequest request) {
    // ← 커넥션 획득
    userValidator.validateSignUp(request.email(), request.name());       // ~5ms
    String encodedPassword = passwordEncoder.encode(request.password()); // ~100ms (커넥션 점유 중)
    User user = User.create(request.name(), request.email(), encodedPassword, request.birthDate());
    User savedUser = userRepository.save(user);                          // ~5ms
    return UserSignUpResponse.from(savedUser);
    // ← 커넥션 반환
}
```

#### 로그인

```java
@Transactional
public UserLoginResponse login(UserLoginRequest request) {
    // ← 커넥션 획득
    User user = userRepository.findByEmail(request.email())             // ~3ms
            .orElseThrow();
    passwordEncoder.matches(request.password(), user.getPassword());    // ~100ms (커넥션 점유 중)
    return UserLoginResponse.from(user);
    // ← 커넥션 반환
}
```

두 API 모두 **BCrypt 연산이 트랜잭션 안에 포함**되어 있다.
커넥션 점유 시간의 97%가 DB와 무관한 CPU 작업이다.

```
커넥션 획득 ──── DB 작업 (~3-10ms) ──── BCrypt (~100ms) ──── 커넥션 반환
|◄───────────────────── 총 ~103-110ms 점유 ─────────────────────►|
                                         ↑
                              여기서 커넥션을 쓰지 않지만 잡고 있음
```

### 2. 흔한 반론에 대한 답 — "BCrypt는 CPU 작업인데 왜 커넥션이 묶이나"

엄밀히 말하면 BCrypt는 DB와 무관한 CPU 연산이고, "BCrypt가 커넥션을 점유한다"는 표현은 부정확하다. 그러나 **`@Transactional`의 생명주기가 그 부정확한 표현을 사실로 만든다**.

Spring의 `@Transactional`은 AOP 프록시로 동작한다.
메서드 진입 시 `JpaTransactionManager.doBegin()`이 호출되고,
Hibernate가 `setAutoCommit(false)`를 실행하기 위해 **커넥션을 즉시 획득**한다.

```
@Transactional 메서드 호출
    │
    ├─ 1. TransactionInterceptor.invoke()
    │
    ├─ 2. JpaTransactionManager.doBegin()
    │      └─ EntityManager.getTransaction().begin()
    │          └─ Hibernate TransactionImpl.begin()
    │              └─ Connection 획득   ← setAutoCommit(false) 호출을 위해
    │
    ├─ 3. 실제 메서드 실행 (findByEmail + BCrypt)
    │      └─ BCrypt 실행 중에도 Connection은 반환되지 않음
    │
    └─ 4. JpaTransactionManager.doCommit()
           └─ Connection 반환
```

2번과 4번 사이의 **모든 코드가 커넥션을 점유**한다.
BCrypt가 DB를 안 쓰더라도, `@Transactional`의 생명주기 안에 있으면 커넥션 반환이 불가능하다.

비유하면 이렇다:

```
호텔 방(커넥션)을 체크인(트랜잭션 시작)한 상태에서
방 안에서 잠을 자든, 밖에서 산책을 하든(BCrypt)
체크아웃(트랜잭션 종료)하기 전까지 그 방은 다른 손님이 못 쓴다.
```

문제는 "BCrypt가 방을 쓰느냐"가 아니라, **"체크아웃 전에 BCrypt가 끼어있느냐"**다.

### 3. 변경 후 코드 — TransactionTemplate으로 트랜잭션 범위 최소화

해결 방법은 단순하다. **BCrypt를 트랜잭션 밖으로 꺼내면 된다.**

`TransactionTemplate`은 `.execute()` 블록 단위로 트랜잭션을 열고 닫는다.
블록이 끝나면 즉시 커밋하고 커넥션을 반환한다.

#### 회원가입

```java
public UserSignUpResponse signUp(UserSignUpRequest request) {

    // BCrypt 해싱: 트랜잭션 밖에서 실행 → 커넥션 점유 안 함
    String encodedPassword = passwordEncoder.encode(request.password()); // ~100ms

    // 검증 + 저장: 트랜잭션 안에서 실행 → 커넥션 점유 최소화
    User savedUser = transactionTemplate.execute(status -> {
        userValidator.validateSignUp(request.email(), request.name());
        User user = User.create(
                request.name(), request.email(), encodedPassword, request.birthDate()
        );
        return userRepository.save(user);
    });

    return UserSignUpResponse.from(savedUser);
}
```

#### 로그인

```java
public UserLoginResponse login(UserLoginRequest request) {

    // 1. 트랜잭션 안: 유저 조회만 (짧은 DB 작업 → 커넥션 빠르게 반환)
    User user = Objects.requireNonNull(
            transactionTemplate.execute(status ->
                    userRepository.findByEmail(request.email())
                            .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이메일입니다"))
            )
    );

    // 2. 트랜잭션 밖: BCrypt 검증 (커넥션 점유 안 함)
    if (!passwordEncoder.matches(request.password(), user.getPassword())) {
        throw new IllegalArgumentException("비밀번호가 일치하지 않습니다");
    }

    return UserLoginResponse.from(user);
}
```

#### 커넥션 점유 타임라인 비교

```
[변경 전]
커넥션 획득 ──── DB 작업 (~3ms) ──── BCrypt (~100ms) ──── 커넥션 반환
|◄───────────────────── 총 ~103ms 점유 ─────────────────────►|

[변경 후]
커넥션 획득 ── DB 작업 (~3ms) ── 커넥션 반환        BCrypt (~100ms)
|◄────── 총 ~3ms 점유 ──────►|                    |◄── 커넥션 없이 실행 ──►|
```

### 4. 대안 검토 — "@Transactional(readOnly = true)로는 왜 부족한가"

이 시점에서 가장 자주 나오는 대안은 `@Transactional(readOnly = true)`이다. 두 옵션을 같은 기준으로 비교한다.

readOnly = true의 장점은 분명하다:
- Hibernate **더티 체킹 비활성화** → 스냅샷 미생성으로 메모리/CPU 절약
- MySQL InnoDB **읽기 전용 최적화** 적용
- 코드가 간결하고 **"데이터를 변경하지 않는다"**는 의도가 명확

**하지만 `readOnly = true`는 트랜잭션의 "성격"을 바꿀 뿐, "범위"는 바꾸지 않는다.**

```
@Transactional(readOnly = true)
│
├─ 메서드 진입 → 커넥션 획득 (setAutoCommit(false))
│
├─ findByEmail()       ~3ms     ← 커넥션 점유 중
├─ BCrypt.verify()     ~100ms   ← 커넥션 점유 중 (DB 안 쓰지만 반환 안 됨)
│
└─ 메서드 종료 → 커넥션 반환
```

readOnly든 아니든, `@Transactional`이 붙은 메서드는 **진입부터 종료까지** 커넥션을 잡는다.

| 방식 | 커넥션 점유 시간 | 풀 10개 처리량 | 더티 체킹 |
|---|---|---|---|
| `@Transactional` | ~103ms | ~97 req/s | O (오버헤드) |
| `@Transactional(readOnly = true)` | ~103ms | ~97 req/s | X (절약) |
| `TransactionTemplate` | ~3ms | ~3,330 req/s | X (조회만 수행) |

**핵심 통찰**: `readOnly = true`는 트랜잭션의 **성격**을 바꾸지만 **범위**는 바꾸지 않는다. 더티 체킹 비용은 제거되지만 커넥션 점유 시간은 그대로다. 이 차이가 "최적화의 차원"을 결정한다 — readOnly는 트랜잭션 안의 비용을 깎고, TransactionTemplate은 트랜잭션 자체를 짧게 만든다. **본질적 병목(커넥션 점유 시간)을 직접 다루지 않는 최적화는 처리량 한계를 바꾸지 못한다.**

### 5. 처리량 개선 수치

| 항목 | 변경 전 (@Transactional) | 변경 후 (TransactionTemplate) | 개선율 |
|---|---|---|---|
| 커넥션 점유 시간 | ~103ms | ~3ms | **약 34배 감소** |
| 풀 10개 처리량 | ~97 req/s | ~3,330 req/s | **약 34배 증가** |
| 풀 20개 처리량 | ~194 req/s | ~6,660 req/s | **약 34배 증가** |
| 동시 100건 요청 (풀 5개) | 타임아웃 발생 | 전부 성공 | - |

> 위 수치는 커넥션 풀 관점의 이론적 처리량이다. 실제 최대 처리량은 아래 CPU 분석 참고.

### 6. 실제 병목 — CPU 바운드와 "최적화의 진짜 가치"

커넥션 풀 병목은 해결했지만, BCrypt 자체가 **CPU 바운드** 작업이다. 실제 서버의 최대 처리량은 결국 CPU 코어 수에 의해 결정된다.

| CPU 코어 | BCrypt cost=10 (~100ms) | BCrypt cost=12 (~400ms) |
|---|---|---|
| 2코어 | ~20 req/s | ~5 req/s |
| 4코어 | ~40 req/s | ~10 req/s |
| 8코어 | ~80 req/s | ~20 req/s |
| 16코어 | ~160 req/s | ~40 req/s |

CPU가 어차피 한계이면 이 최적화는 의미가 없는 게 아닌가? — 이 질문이 이 글의 가장 중요한 의사결정 지점이다.

> **이 최적화의 진짜 가치는 "로그인 처리량 향상"이 아니라 "장애 격리"에 있다.**

| 측면 | 변경 전 | 변경 후 |
|------|--------|--------|
| 로그인 자체의 처리량 상한 | CPU + 커넥션 풀 (둘 중 더 작은 쪽) | CPU만 |
| 다른 API에 미치는 영향 | 풀 고갈로 전체 장애 전파 | 0 (커넥션 풀 무관) |
| 장애 격리 | 불가능 | 가능 |

이 표가 의미하는 바는 결정적이다. **로그인 트래픽이 폭증해도 서비스의 다른 부분은 영향을 받지 않는 구조**가 만들어진다 — 즉 CPU 바운드라는 본질적 한계는 그대로지만, 그 한계가 시스템 전체로 전파되지 않는다. 단일 도메인의 비용이 시스템 전체 가용성을 결정하던 구조가, 단일 도메인 안에 격리된 구조로 바뀐다.

```
[변경 전] 로그인 폭주 → 커넥션 풀 고갈 → 상품 조회, 주문 등 다른 API도 전부 대기
[변경 후] 로그인 폭주 → 커넥션 3ms씩만 사용 → 다른 API는 정상 동작
```

이 차이는 단순한 성능 개선이 아니라 **장애 영향 범위(blast radius)의 축소**다. 대용량 트래픽 서버에서 가장 본질적인 가치 중 하나다.

### 7. 테스트 검증

HikariCP 풀 5개 + 동시 100건 요청으로 변경 전/후를 검증했다.

| 시나리오 | 성공 | 타임아웃 | 결과 |
|---|---|---|---|
| 변경 전 (BCrypt IN 트랜잭션) | 일부 | 다수 발생 | **커넥션 풀 고갈** |
| 변경 후 (BCrypt OUT 트랜잭션) | 100건 전부 | 0건 | **안정적 처리** |

테스트 코드는 `BcryptConnectionTest`, `LoginBcryptConnectionTest`에서 확인할 수 있다.
Spring Context 없이 순수 JDBC + HikariCP로 작성하여, 커넥션 풀 동작만 격리해서 검증했다.

---

## 마무리

### 설계 요약

```
┌─────────────────────────────────────────────────────────┐
│                      요청 수신                           │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  [회원가입]                     [로그인]                  │
│                                                         │
│  BCrypt.hash() (~100ms)        ┌── TransactionTemplate ─┐│
│  ← 커넥션 없이 실행             │ findByEmail() (~3ms)   ││
│                                └────────────────────────┘│
│  ┌── TransactionTemplate ──┐   ↓ 커넥션 반환              │
│  │ validateSignUp()        │                             │
│  │ userRepository.save()   │   BCrypt.verify() (~100ms)  │
│  └─────────────────────────┘   ← 커넥션 없이 실행         │
│  ↓ 커넥션 반환                                            │
│                                                         │
│  응답 반환                      응답 반환                  │
└─────────────────────────────────────────────────────────┘
```

### TransactionTemplate을 선택한 이유

| 이유 | 설명 |
|---|---|
| **커넥션 격리** | CPU 작업(BCrypt)을 DB 커넥션 생명주기에서 완전 분리 |
| **장애 전파 차단** | 로그인 폭주가 다른 API의 커넥션 고갈로 이어지지 않음 |
| **서비스 전체 안정성** | 커넥션 풀은 모든 API가 공유하는 자원. 한 API가 독점하면 전체가 멈춤 |
| **코드 복잡도 대비 효과** | 3~4줄 추가로 커넥션 처리량 34배 개선 |

### 핵심 한 줄

> **BCrypt를 `@Transactional` 밖으로 꺼내는 것은 단순한 성능 최적화가 아니라, 인증 도메인의 비용 모델이 시스템 전체 가용성에 영향을 주지 않도록 만드는 장애 격리 설계다.** 커넥션 점유 시간을 103ms에서 3ms로 줄이는 것은 그 결과로 따라오는 부수적 효과다.

### 적용 기술

| 기술 | 역할 |
|---|---|
| `TransactionTemplate` | 트랜잭션 범위를 DB 작업으로만 한정 |
| `BCrypt cost=10` | 보안과 성능의 균형 (cost=12 대비 약 4배 빠름) |
| `Objects.requireNonNull()` | TransactionTemplate.execute()의 @Nullable 반환 처리 |
| `HikariCP 커넥션 풀 테스트` | 변경 전/후 커넥션 고갈 여부를 수치로 검증 |
