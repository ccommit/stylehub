# 로그인 API 설계 및 성능 최적화

## 1. 문제 인식

로그인 API는 서비스 내에서 **가장 호출 빈도가 높은 API** 중 하나다.
핵심 병목은 **BCrypt 비밀번호 검증**으로, cost=10 기준 1회당 **약 80~100ms**의 CPU 시간을 소모한다.

만약 이 BCrypt 연산이 DB 트랜잭션(= DB 커넥션 점유) 안에서 실행된다면,
**커넥션 1개당 100ms 이상 점유**하게 되어 커넥션 풀이 빠르게 고갈된다.

---

## 2. 변경 전 — 일반적인 @Transactional 방식

```java
@Transactional
public UserLoginResponse login(UserLoginRequest request) {
    // ← 여기서 커넥션 획득 (setAutoCommit(false))

    User user = userRepository.findByEmail(request.email())  // ~3ms
            .orElseThrow(...);

    passwordEncoder.matches(request.password(), user.getPassword()); // ~100ms (커넥션 점유 중)

    return UserLoginResponse.from(user);
    // ← 여기서 커넥션 반환
}
```

### 커넥션 점유 타임라인 (변경 전)

```
커넥션 획득 ──── SELECT (~3ms) ──── BCrypt verify (~100ms) ──── 커넥션 반환
|◄──────────────────── 총 ~103ms 점유 ────────────────────►|
```

### 처리량 계산 (변경 전)

| 항목 | 값 |
|---|---|
| 커넥션 1개 점유 시간 | ~103ms |
| 커넥션 1개 초당 처리량 | 1000 / 103 ≈ **9.7 req/s** |
| HikariCP 풀 10개 | 9.7 × 10 = **~97 req/s** |
| HikariCP 풀 20개 | 9.7 × 20 = **~194 req/s** |

**문제: 동시 요청 100개만 들어와도 커넥션 풀 대기 → 타임아웃 발생**

---

## 3. 변경 후 — TransactionTemplate으로 트랜잭션 범위 최소화

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

### 커넥션 점유 타임라인 (변경 후)

```
커넥션 획득 ── SELECT (~3ms) ── 커넥션 반환        BCrypt verify (~100ms)
|◄────── 총 ~3ms 점유 ──────►|                    |◄── 커넥션 없이 실행 ──►|
```

### 처리량 계산 (변경 후)

| 항목 | 값 |
|---|---|
| 커넥션 1개 점유 시간 | ~3ms |
| 커넥션 1개 초당 처리량 | 1000 / 3 ≈ **333 req/s** |
| HikariCP 풀 10개 | 333 × 10 = **~3,330 req/s** |
| HikariCP 풀 20개 | 333 × 20 = **~6,660 req/s** |

---

## 4. 변경 전/후 비교

| 항목 | 변경 전 (@Transactional) | 변경 후 (TransactionTemplate) | 개선율 |
|---|---|---|---|
| 커넥션 점유 시간 | ~103ms | ~3ms | **약 34배 감소** |
| 풀 10개 처리량 | ~97 req/s | ~3,330 req/s | **약 34배 증가** |
| 풀 20개 처리량 | ~194 req/s | ~6,660 req/s | **약 34배 증가** |
| 동시 100건 요청 (풀 5개) | 타임아웃 발생 | 전부 성공 | - |

> 커넥션 풀 관점에서의 이론적 처리량이다. 실제 병목은 아래 CPU 분석 참고.

---

## 5. 실제 병목 분석 — CPU 바운드

커넥션 풀 병목은 해결했지만, BCrypt 자체가 **CPU 바운드** 작업이다.
실제 서버의 최대 로그인 처리량은 CPU 코어 수에 의해 결정된다.

### CPU 기준 처리량

| CPU 코어 | BCrypt cost=10 (~100ms) | BCrypt cost=12 (~400ms) |
|---|---|---|
| 2코어 | ~20 req/s | ~5 req/s |
| 4코어 | ~40 req/s | ~10 req/s |
| 8코어 | ~80 req/s | ~20 req/s |
| 16코어 | ~160 req/s | ~40 req/s |

### 핵심 포인트

변경 전에는 **커넥션 풀 + CPU** 두 가지 모두 병목이었다.
변경 후에는 **CPU만 병목**이 되며, 커넥션 풀은 여유가 생겨 **다른 API에도 영향을 주지 않는다.**

```
[변경 전] 로그인 100건 → 커넥션 풀 고갈 → 상품 조회, 주문 등 다른 API도 전부 대기
[변경 후] 로그인 100건 → 커넥션 3ms씩만 사용 → 다른 API는 정상 동작
```

이것이 이 최적화의 **진짜 가치**다. 로그인 트래픽 급증이 전체 서비스 장애로 이어지지 않는다.

---

## 6. 테스트 검증 결과

`LoginBcryptConnectionTest`에서 HikariCP 풀 5개 + 동시 100건 요청으로 검증:

| 시나리오 | 성공 | 타임아웃 | 결과 |
|---|---|---|---|
| 변경 전 (BCrypt IN 트랜잭션) | 일부 | 다수 발생 | **커넥션 풀 고갈** |
| 변경 후 (BCrypt OUT 트랜잭션) | 100건 전부 | 0건 | **안정적 처리** |

---

## 7. @Transactional(readOnly = true)로 충분하지 않은가?

### 반론: "읽기 전용이니까 @Transactional(readOnly = true)가 더 적절하다"

```java
@Transactional(readOnly = true)
public UserLoginResponse login(UserLoginRequest request) {
    User user = userRepository.findByEmail(request.email())
            .orElseThrow(...);

    passwordEncoder.matches(request.password(), user.getPassword()); // ~100ms
    return UserLoginResponse.from(user);
}
```

readOnly = true의 장점은 분명하다:
- Hibernate **더티 체킹 비활성화** → 스냅샷 미생성으로 메모리/CPU 절약
- MySQL InnoDB **읽기 전용 최적화** 적용
- 코드가 간결하고 **"이 메서드는 데이터를 변경하지 않는다"**는 의도가 명확

**하지만 커넥션 점유 문제는 해결하지 못한다.**

### readOnly = true여도 커넥션은 점유된다

`@Transactional(readOnly = true)`는 트랜잭션의 **성격**을 바꿀 뿐, **범위**는 바꾸지 않는다.

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
BCrypt 100ms 동안 커넥션이 아무 일 없이 점유되는 문제는 동일하다.

### 수치로 비교

| 방식 | 커넥션 점유 시간 | 풀 10개 처리량 | 더티 체킹 |
|---|---|---|---|
| `@Transactional` | ~103ms | ~97 req/s | O (불필요한 오버헤드) |
| `@Transactional(readOnly = true)` | ~103ms | ~97 req/s | X (절약) |
| `TransactionTemplate` | ~3ms | ~3,330 req/s | X (조회만 수행) |

readOnly는 더티 체킹을 제거하지만, **커넥션 처리량은 @Transactional과 동일하다.**
TransactionTemplate은 커넥션 점유 시간 자체를 **34배 줄인다.** 차원이 다른 최적화다.

### 그래서 TransactionTemplate을 선택한 이유

1. **커넥션 격리**: BCrypt라는 CPU 작업을 DB 커넥션 생명주기에서 완전히 분리한다.
   로그인은 "DB 조회 + CPU 연산"이 결합된 API인데, 이 둘의 리소스 특성이 전혀 다르다.
   같은 트랜잭션에 묶으면 느린 쪽(BCrypt)이 빠른 쪽(DB)의 리소스를 불필요하게 점유한다.

2. **서비스 전체 안정성**: 커넥션 풀은 로그인만 쓰는 게 아니다.
   상품 조회, 주문, 결제 등 모든 API가 같은 풀을 공유한다.
   로그인에서 커넥션을 100ms씩 점유하면, 트래픽 급증 시 **다른 API까지 연쇄 장애**가 발생한다.
   TransactionTemplate으로 3ms만 점유하면 나머지 97ms 동안 다른 API가 커넥션을 쓸 수 있다.

3. **장애 전파 차단**: 대용량 트래픽 환경에서 가장 위험한 것은 **하나의 병목이 전체 시스템을 마비**시키는 것이다.
   커넥션 풀 고갈은 대표적인 장애 전파 패턴이며, TransactionTemplate은 이 전파 경로를 차단한다.

   ```
   [readOnly = true]  로그인 폭주 → 커넥션 풀 고갈 → 주문 API 타임아웃 → 전체 장애
   [TransactionTemplate] 로그인 폭주 → 커넥션 3ms 사용 → 주문 API 정상 → 장애 격리
   ```

4. **코드 복잡도 대비 효과**: TransactionTemplate으로 인해 늘어나는 코드는 3~4줄에 불과하다.
   이 3~4줄로 커넥션 처리량 34배 개선과 장애 격리를 얻는다면, 충분히 합리적인 트레이드오프다.

---

## 8. "BCrypt는 CPU 작업이라 커넥션 점유와 무관하지 않나?"

### 결론부터: BCrypt 자체는 커넥션을 안 쓰지만, @Transactional이 메서드 진입 시점에 커넥션을 먼저 획득하기 때문에 점유와 "관련이 있다."

BCrypt는 순수 CPU 연산이다. DB에 쿼리를 날리지 않는다.
그래서 "BCrypt가 커넥션을 점유한다"는 표현은 엄밀히 틀리다.

**하지만 @Transactional의 동작 방식 때문에 결과적으로 점유하게 된다.**

### @Transactional의 커넥션 획득 시점

Spring의 `@Transactional`은 AOP 프록시로 동작한다.
메서드 진입 시 `JpaTransactionManager.doBegin()`이 호출되고,
이 과정에서 Hibernate가 **`setAutoCommit(false)`를 실행하기 위해 커넥션을 즉시 획득**한다.

```
@Transactional 메서드 호출
    │
    ├─ 1. TransactionInterceptor.invoke()
    │
    ├─ 2. JpaTransactionManager.doBegin()
    │      └─ EntityManager.getTransaction().begin()
    │          └─ Hibernate TransactionImpl.begin()
    │              └─ Connection 획득 ← setAutoCommit(false) 호출을 위해
    │
    ├─ 3. 실제 메서드 실행 (findByEmail + BCrypt)
    │      └─ BCrypt 실행 중에도 Connection은 반환되지 않음
    │
    └─ 4. JpaTransactionManager.doCommit()
           └─ Connection 반환
```

핵심은 **2번과 4번 사이의 모든 코드가 커넥션을 점유**한다는 것이다.
BCrypt가 DB를 안 쓰더라도, @Transactional의 생명주기 안에 있으면 커넥션 반환이 불가능하다.

### 비유

```
호텔 방(커넥션)을 체크인(트랜잭션 시작)한 상태에서
방 안에서 잠을 자든, 밖에서 산책을 하든(BCrypt)
체크아웃(트랜잭션 종료)하기 전까지 그 방은 다른 손님이 못 쓴다.
```

문제는 "BCrypt가 방을 쓰느냐"가 아니라, **"체크아웃 전에 BCrypt가 끼어있느냐"**다.

### TransactionTemplate이 이 문제를 해결하는 방식

TransactionTemplate은 `.execute()` 블록 단위로 트랜잭션을 연다.
블록이 끝나면 즉시 커밋하고 커넥션을 반환한다.

```java
// execute() 시작 → 커넥션 획득
User user = transactionTemplate.execute(status ->
        userRepository.findByEmail(request.email())  // ~3ms
                .orElseThrow(...)
);
// execute() 종료 → 커넥션 반환 (여기서 이미 체크아웃)

// 커넥션 없이 실행
passwordEncoder.matches(request.password(), user.getPassword()); // ~100ms
```

```
TransactionTemplate.execute()
│
├─ 커넥션 획득
├─ findByEmail()   ~3ms
├─ 커넥션 반환              ← 여기서 즉시 반환
│
BCrypt.verify()    ~100ms   ← 커넥션 없음, 풀에 영향 없음
```

BCrypt를 @Transactional의 생명주기 **밖으로 꺼내는 것**이 이 설계의 핵심이다.
"BCrypt가 커넥션을 쓰느냐"가 아니라 **"@Transactional의 커넥션 생명주기 안에 BCrypt가 포함되느냐"**가 진짜 질문이고, TransactionTemplate은 이 포함 관계를 끊는다.

---

## 9. 설계 요약

```
┌─────────────────────────────────────────────────────┐
│                    로그인 요청                        │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌─── TransactionTemplate ───┐                      │
│  │                           │                      │
│  │  findByEmail() (~3ms)     │  ← 커넥션 점유 구간   │
│  │                           │                      │
│  └───────────────────────────┘                      │
│              ↓ 커넥션 반환                            │
│                                                     │
│  BCrypt.verify() (~100ms)       ← 커넥션 없이 실행   │
│              ↓                                      │
│                                                     │
│  응답 반환                                           │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### 적용 기술

| 기술 | 역할 |
|---|---|
| `TransactionTemplate` | 트랜잭션 범위를 DB 작업으로만 한정 |
| `BCrypt cost=10` | 보안과 성능의 균형 (cost=12 대비 약 4배 빠름) |
| `Objects.requireNonNull()` | TransactionTemplate.execute()의 @Nullable 반환 처리 |

### 왜 @Transactional이 아닌 TransactionTemplate인가?

`@Transactional`은 메서드 진입 시점에 커넥션을 획득한다.
Hibernate가 `setAutoCommit(false)`를 호출하기 위해 **실제 DB 쿼리 전에도 커넥션을 먼저 가져온다.**
따라서 `@Transactional` 메서드 안에서 BCrypt를 실행하면, BCrypt 동안 커넥션이 점유된다.

`TransactionTemplate`은 `.execute()` 블록 안에서만 트랜잭션(= 커넥션)이 열리므로,
블록 밖의 BCrypt는 커넥션과 무관하게 실행된다.
