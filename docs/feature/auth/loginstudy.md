# UserService 코드 한 줄 한 줄 분석

## 전체 코드

```java
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserValidator userValidator;
    private final TransactionTemplate transactionTemplate;

    public UserSignUpResponse signUp(UserSignUpRequest request) {

        String encodedPassword = passwordEncoder.encode(request.password());

        User savedUser = transactionTemplate.execute(status -> {
            userValidator.validateSignUp(request.email(), request.name());

            User user = User.create(
                    request.name(),
                    request.email(),
                    encodedPassword,
                    request.birthDate()
            );

            return userRepository.save(user);
        });

        return UserSignUpResponse.from(savedUser);
    }

    public UserLoginResponse login(UserLoginRequest request) {

        User user = Objects.requireNonNull(
                transactionTemplate.execute(status ->
                        userRepository.findByEmail(request.email())
                                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이메일입니다"))
                )
        );

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다");
        }

        transactionTemplate.executeWithoutResult(status -> {
            user.rewardLoginPoint(LocalDate.now());
        });

        return UserLoginResponse.from(user);
    }
}
```

---

## 1. 클래스 선언부

```java
@Service
@RequiredArgsConstructor
public class UserService {
```

### `@Service`

- Spring의 서비스 계층 빈으로 등록한다.
- `@Component`와 기능은 동일하지만, **"이 클래스는 비즈니스 로직을 담당한다"**는 의도를 명시한다.

### `@RequiredArgsConstructor`

- Lombok이 `final` 필드에 대한 **생성자를 자동 생성**한다.
- Spring이 생성자가 1개면 자동으로 `@Autowired`를 적용하므로, 별도 어노테이션 없이 DI가 된다.

---

## 2. 의존성 주입

```java
private final UserRepository userRepository;
private final PasswordEncoder passwordEncoder;
private final UserValidator userValidator;
private final TransactionTemplate transactionTemplate;
```

| 필드 | 역할 |
|---|---|
| `userRepository` | DB 접근 (JPA Repository) |
| `passwordEncoder` | BCrypt 해싱/검증 (Spring Security 미사용, 자체 구현) |
| `userValidator` | 회원가입 시 이메일/닉네임 중복 검증 |
| `transactionTemplate` | 프로그래밍 방식 트랜잭션 관리 |

### 왜 `TransactionTemplate`인가?

`@Transactional`은 메서드 진입 시점에 커넥션을 획득한다.
BCrypt가 메서드 안에 있으면 ~100ms 동안 커넥션이 불필요하게 점유된다.
`TransactionTemplate`은 `.execute()` 블록 안에서만 커넥션을 사용하므로,
**BCrypt를 트랜잭션 밖에 배치하여 커넥션 점유 시간을 최소화**할 수 있다.

### 왜 `PasswordEncoder`를 자체 구현했나?

Spring Security의 `PasswordEncoder`를 사용하려면 `spring-boot-starter-security` 의존성이 필요하다.
이 프로젝트는 Spring Security를 사용하지 않으므로, `at.favre.lib:bcrypt` 라이브러리로 직접 구현했다.
내부적으로 BCrypt cost=10을 사용한다 (~80-100ms).

---

## 3. 회원가입 — `signUp()`

### 3-1. BCrypt 해싱 (트랜잭션 밖)

```java
String encodedPassword = passwordEncoder.encode(request.password());
```

**왜 트랜잭션 밖인가?**

- BCrypt 해싱은 ~100ms가 걸리는 순수 CPU 작업이다.
- 이 시점에는 아직 `transactionTemplate.execute()`가 호출되지 않았으므로 **커넥션이 획득되지 않은 상태**다.
- 만약 `@Transactional` 메서드 안에서 실행했다면, 해싱 100ms 동안 커넥션이 점유된다.

**해결한 문제:** 커넥션 풀 고갈 방지. 커넥션 점유 시간 ~110ms → ~10ms로 감소.

### 3-2. 검증 + 저장 (트랜잭션 안)

```java
User savedUser = transactionTemplate.execute(status -> {
```

- `transactionTemplate.execute()` 호출 시점에 **커넥션을 획득하고 트랜잭션을 시작**한다.
- 람다 안의 코드가 모두 실행된 후 **자동으로 커밋하고 커넥션을 반환**한다.
- 예외 발생 시 자동 롤백된다.

```java
    userValidator.validateSignUp(request.email(), request.name());
```

- 이메일 중복 체크: `SELECT COUNT(*) FROM users WHERE email = ?`
- 닉네임 중복 체크: `SELECT COUNT(*) FROM users WHERE name = ?`
- 중복이면 `IllegalArgumentException`을 던지고, 트랜잭션은 롤백된다.

**왜 트랜잭션 안에서 검증하는가?**

검증과 저장 사이에 다른 요청이 같은 이메일로 가입할 수 있다 (race condition).
같은 트랜잭션 안에서 검증 + 저장을 하면 DB의 unique 제약조건과 함께 **이중 방어**가 된다.

```java
    User user = User.create(
            request.name(),
            request.email(),
            encodedPassword,
            request.birthDate()
    );
```

- 정적 팩토리 메서드로 User 엔티티를 생성한다.
- `new User(...)` 대신 `User.create()`를 사용한 이유:
  - **생성 의도가 명확**하다 ("회원가입용 유저 생성")
  - `User.createOAuth()`와 구분된다
  - `@Builder.Default`로 설정된 기본값 (role=USER, grade=BRONZE, pointBalance=0 등)이 자동 적용된다

```java
    return userRepository.save(user);
});
```

- JPA `save()`로 INSERT 실행.
- 람다가 끝나면 트랜잭션 커밋 → 커넥션 반환.

### 3-3. 응답 변환

```java
return UserSignUpResponse.from(savedUser);
```

- 엔티티를 응답 DTO로 변환한다.
- 트랜잭션이 이미 끝난 상태에서 실행되므로 커넥션을 사용하지 않는다.

---

## 4. 로그인 — `login()`

### 4-1. 유저 조회 (트랜잭션 1 — 읽기)

```java
User user = Objects.requireNonNull(
        transactionTemplate.execute(status ->
                userRepository.findByEmail(request.email())
                        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이메일입니다"))
        )
);
```

**한 줄씩 분해:**

| 코드 | 설명 |
|---|---|
| `transactionTemplate.execute(status -> ...)` | 트랜잭션 시작 → 커넥션 획득 |
| `userRepository.findByEmail(request.email())` | `SELECT * FROM users WHERE email = ?` (~3ms) |
| `.orElseThrow(...)` | 유저가 없으면 예외. 트랜잭션 롤백 + 커넥션 반환 |
| `)` | 람다 종료 → 트랜잭션 커밋 → **커넥션 반환** |
| `Objects.requireNonNull(...)` | `TransactionTemplate.execute()`의 반환 타입이 `@Nullable`이므로 null 안전성 보장 |

**왜 `Objects.requireNonNull()`을 감싸는가?**

`TransactionTemplate.execute()`의 시그니처:

```java
@Nullable
<T> T execute(TransactionCallback<T> action)
```

반환 타입이 `@Nullable`이다. 실제로 우리 코드에서 null이 반환될 일은 없지만 (orElseThrow로 보장),
컴파일러 경고를 방지하고 **"이 값은 절대 null이 아니다"**라는 의도를 명시한다.

**커넥션 점유 시간:** ~3ms (SELECT 1건). 이 블록이 끝나면 커넥션이 즉시 반환된다.

### 4-2. BCrypt 비밀번호 검증 (트랜잭션 밖)

```java
if (!passwordEncoder.matches(request.password(), user.getPassword())) {
    throw new IllegalArgumentException("비밀번호가 일치하지 않습니다");
}
```

**왜 트랜잭션 밖인가?**

- `BCrypt.verify()`는 ~100ms가 걸리는 CPU 작업이다.
- 4-1의 트랜잭션은 이미 끝났으므로 **커넥션이 반환된 상태**다.
- 이 100ms 동안 커넥션 풀에 영향이 전혀 없다.

**이것이 이 설계의 핵심이다.**

```
[트랜잭션 1]  커넥션 획득 → SELECT (~3ms) → 커넥션 반환
[트랜잭션 밖] BCrypt verify (~100ms) → 커넥션 없음
[트랜잭션 2]  커넥션 획득 → UPDATE (~3ms) → 커넥션 반환
```

총 커넥션 점유 시간: ~6ms. `@Transactional`이었다면 ~106ms.

**`matches()` 내부 동작:**

```java
public boolean matches(String password, String hashedPassword) {
    return BCrypt.verifyer().verify(password.toCharArray(), hashedPassword).verified;
}
```

- 입력된 평문 비밀번호를 해시된 비밀번호와 비교한다.
- 내부적으로 동일한 salt + cost로 해싱 후 결과를 비교한다.
- 해싱과 동일하게 ~100ms 소요.

### 4-3. 로그인 포인트 지급 (트랜잭션 2 — 쓰기)

```java
transactionTemplate.executeWithoutResult(status -> {
    user.rewardLoginPoint(LocalDate.now());
});
```

**`executeWithoutResult` vs `execute`:**

- `execute()`: 반환값이 있는 경우 (`TransactionCallback<T>`)
- `executeWithoutResult()`: 반환값이 없는 경우 (`Consumer<TransactionStatus>`)
- 포인트 지급은 엔티티 상태만 변경하고 반환할 값이 없으므로 `executeWithoutResult` 사용.

**`user.rewardLoginPoint(LocalDate.now())` 내부:**

```java
public void rewardLoginPoint(LocalDate today) {
    if (role == Role.ADMIN) {
        return;                          // Admin은 포인트 지급 안 함
    }

    if (lastLoginDate == null) {
        pointBalance += FIRST_LOGIN_POINT; // 첫 로그인: 1000P
    } else if (!lastLoginDate.equals(today)) {
        pointBalance += DAILY_LOGIN_POINT; // 하루 1회: 10P
    }
    // 같은 날 재로그인: 아무것도 안 함
    lastLoginDate = today;
}
```

**왜 포인트 로직이 엔티티 안에 있는가?**

- 포인트 지급 판단은 User의 상태(`lastLoginDate`, `role`)에 의존한다.
- 서비스에서 `if (user.getLastLoginDate() == null) ...` 하면 **엔티티의 내부 상태를 서비스가 직접 판단**하게 된다.
- 엔티티 안에 두면 **캡슐화**가 유지되고, 서비스는 `user.rewardLoginPoint(today)` 한 줄만 호출하면 된다.

**왜 별도 트랜잭션인가?**

- 포인트 지급은 DB UPDATE가 필요하다 (pointBalance, lastLoginDate 변경).
- JPA 더티 체킹: 트랜잭션 안에서 엔티티 필드를 변경하면, 트랜잭션 커밋 시 자동으로 UPDATE 쿼리가 실행된다.
- 이 트랜잭션도 ~3ms면 끝난다.

**왜 4-1 트랜잭션과 합치지 않는가?**

합치면 BCrypt가 트랜잭션 안에 들어간다:

```java
// 이렇게 하면 안 됨
transactionTemplate.execute(status -> {
    User user = userRepository.findByEmail(...);    // ~3ms
    passwordEncoder.matches(...);                    // ~100ms ← 커넥션 점유 중!
    user.rewardLoginPoint(today);                    // ~1ms
    return user;                                     // ~3ms UPDATE
});
// 총 커넥션 점유: ~107ms
```

분리함으로써 BCrypt 100ms 동안 커넥션을 점유하지 않는다.

### 4-4. 응답 반환

```java
return UserLoginResponse.from(user);
```

- 엔티티를 응답 DTO로 변환. userId, name, email, grade, pointBalance를 포함한다.
- 트랜잭션이 끝난 상태이므로 커넥션 사용 없음.

---

## 5. 전체 로그인 플로우 타임라인

```
시간 →

0ms          3ms                          103ms        106ms
 |            |                             |            |
 |◄─ TX1 ──►|                             |◄─ TX2 ──►|
 | SELECT    |     BCrypt verify           | UPDATE    |
 | 커넥션 점유 |     커넥션 없음 (CPU만 사용)  | 커넥션 점유 |
 |            |                             |            |

총 커넥션 점유: ~6ms
총 응답 시간: ~106ms
커넥션 절약: 100ms (BCrypt 시간 전부)
```

---

## 6. 예상 질문 & 답변

### Q1. 왜 `@Transactional` 대신 `TransactionTemplate`을 사용했나요?

`@Transactional`은 메서드 진입 시점에 커넥션을 획득한다. Hibernate가 `setAutoCommit(false)`를 호출하기 위해 실제 쿼리 전에도 커넥션을 먼저 가져온다. BCrypt가 메서드 안에 있으면 ~100ms 동안 커넥션이 불필요하게 점유된다.

`TransactionTemplate`은 `.execute()` 블록 안에서만 트랜잭션이 열리므로, 블록 밖의 BCrypt는 커넥션과 무관하게 실행된다. 커넥션 점유 시간이 ~103ms에서 ~6ms로 감소한다.

### Q2. `@Transactional(readOnly = true)`로 충분하지 않나요?

`readOnly = true`는 트랜잭션의 **성격**을 바꿀 뿐, **범위**는 바꾸지 않는다. 메서드 진입부터 종료까지 커넥션을 잡는 것은 동일하다. 더티 체킹 비활성화로 약간의 메모리/CPU는 절약되지만, 커넥션 점유 시간 문제는 해결하지 못한다.

### Q3. BCrypt는 CPU 작업인데 커넥션 점유와 무관하지 않나요?

BCrypt 자체는 커넥션을 안 쓴다. 하지만 `@Transactional`이 메서드 진입 시점에 커넥션을 먼저 획득하기 때문에, BCrypt가 트랜잭션 생명주기 안에 포함되면 결과적으로 커넥션이 점유된다. 문제는 "BCrypt가 커넥션을 쓰느냐"가 아니라 **"@Transactional의 커넥션 생명주기 안에 BCrypt가 포함되느냐"**다.

### Q4. `Objects.requireNonNull()`을 왜 감싸나요?

`TransactionTemplate.execute()`의 반환 타입이 `@Nullable`이기 때문이다. 실제로 null이 반환될 일은 없지만 (orElseThrow로 보장), 컴파일러 경고를 방지하고 null이 아님을 명시적으로 선언한다. 만약 예상치 못하게 null이 반환되면 `NullPointerException`으로 빠르게 실패한다 (fail-fast).

### Q5. 왜 트랜잭션을 2개로 나눴나요? (조회 / 포인트 지급)

하나로 합치면 BCrypt가 두 트랜잭션 사이가 아니라 트랜잭션 안에 들어가게 된다. 분리함으로써:
- 트랜잭션 1 (조회): ~3ms 커넥션 점유
- BCrypt 검증: ~100ms **커넥션 없음**
- 트랜잭션 2 (포인트 지급): ~3ms 커넥션 점유

### Q6. 트랜잭션이 2개면 일관성에 문제가 없나요?

트랜잭션 1(조회)과 트랜잭션 2(포인트 지급) 사이에 정합성 문제가 발생할 수 있는 시나리오를 생각해보면:

- 트랜잭션 1에서 유저를 조회했는데, 트랜잭션 2 전에 유저가 삭제되는 경우?
  → 로그인 중에 유저가 삭제되는 것은 비정상 시나리오. 발생해도 UPDATE 실패로 끝남.
- 같은 유저가 동시에 2번 로그인하여 포인트가 중복 지급되는 경우?
  → `lastLoginDate`가 같은 날이면 포인트를 지급하지 않으므로, 최악의 경우 10P가 한 번 더 지급될 수 있지만 비즈니스적으로 무시 가능한 수준.

**커넥션 점유 34배 감소**라는 이점 대비 감수할 수 있는 트레이드오프다.

### Q7. `executeWithoutResult` vs `execute`의 차이는?

둘 다 트랜잭션 안에서 코드를 실행한다.

| 메서드 | 반환 타입 | 용도 |
|---|---|---|
| `execute(TransactionCallback<T>)` | `T` | 트랜잭션 결과를 반환해야 할 때 (조회 등) |
| `executeWithoutResult(Consumer<TransactionStatus>)` | `void` | 반환값이 없을 때 (업데이트 등) |

### Q8. 왜 `PasswordEncoder`를 Spring Security 것을 안 쓰나요?

Spring Security를 사용하지 않는 프로젝트이다. `spring-boot-starter-security`를 추가하면 자동 보안 설정(모든 엔드포인트 인증 필요 등)이 적용되어 불필요한 설정 작업이 생긴다. `at.favre.lib:bcrypt` 라이브러리로 BCrypt 해싱/검증만 직접 구현했다.

### Q9. 포인트 로직을 왜 서비스가 아닌 엔티티에 넣었나요?

포인트 지급 여부는 User의 내부 상태(`lastLoginDate`, `role`)로 결정된다. 서비스에서 판단하면:

```java
// 안 좋은 예: 서비스에서 엔티티 상태를 직접 꺼내서 판단
if (user.getRole() != Role.ADMIN) {
    if (user.getLastLoginDate() == null) {
        user.setPointBalance(user.getPointBalance() + 1000);
    } else if (!user.getLastLoginDate().equals(today)) {
        user.setPointBalance(user.getPointBalance() + 10);
    }
    user.setLastLoginDate(today);
}
```

엔티티의 캡슐화가 깨지고, setter가 열리며, 로직이 서비스에 흩어진다. 엔티티 안에 두면:

```java
// 좋은 예: 서비스는 명령만, 판단은 엔티티가
user.rewardLoginPoint(today);
```

### Q10. 이 최적화로 실제 얼마나 성능이 개선되나요?

| 항목 | @Transactional | TransactionTemplate |
|---|---|---|
| 커넥션 점유 시간 | ~103ms | ~6ms |
| 풀 10개 처리량 | ~97 req/s | ~1,660 req/s |
| 풀 20개 처리량 | ~194 req/s | ~3,330 req/s |

커넥션 풀 관점에서 **약 17배 개선**. (트랜잭션 2개이므로 6ms 기준 계산)

더 중요한 것은: 로그인 트래픽이 급증해도 커넥션 풀 고갈이 발생하지 않아 **다른 API(상품 조회, 주문 등)에 영향을 주지 않는다.**

### Q11. `User.create()` 정적 팩토리 메서드를 왜 사용하나요?

1. **생성 의도가 명확**: `User.create()` vs `User.createOAuth()` — 어떤 방식으로 생성했는지 메서드 이름에 드러남
2. **생성자 은닉**: `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 직접 `new User()`를 막음
3. **기본값 적용**: `@Builder.Default`로 설정한 role=USER, grade=BRONZE 등이 빌더를 통해 자동 적용

### Q12. 회원가입에서 검증과 저장을 왜 같은 트랜잭션에 넣었나요?

검증(existsByEmail)과 저장(save) 사이에 다른 스레드가 같은 이메일로 가입할 수 있다 (TOCTOU race condition). 같은 트랜잭션 안에 두면:
1. **애플리케이션 레벨**: validateSignUp에서 중복 체크
2. **DB 레벨**: email 컬럼의 unique 제약조건

이중 방어로 동시성 문제를 방지한다.

### Q13. 로그인에서 "이메일이 없다" vs "비밀번호 틀림"을 구분해서 응답하면 보안에 문제가 없나요?

현재는 `IllegalArgumentException`으로 구분하고 있다. 엄밀히 말하면 **이메일 열거 공격(email enumeration)**에 취약하다. 공격자가 "존재하지 않는 이메일" 응답을 보고 특정 이메일의 가입 여부를 확인할 수 있다.

프로덕션에서는 "이메일 또는 비밀번호가 올바르지 않습니다"로 통일하는 것이 일반적이다. 현재는 개발/디버깅 편의를 위해 구분해둔 상태이며, 글로벌 예외 처리 PR에서 개선 예정이다.

### Q14. `transactionTemplate.executeWithoutResult` 안에서 `user.rewardLoginPoint()`만 호출하는데, JPA가 어떻게 UPDATE를 실행하나요?

JPA의 **더티 체킹(dirty checking)** 메커니즘이다.

1. 트랜잭션 1에서 `findByEmail()`로 조회한 User 엔티티는 **영속성 컨텍스트에 관리**되는 상태다.
2. 트랜잭션 2에서 `rewardLoginPoint()`를 호출하면 엔티티의 `pointBalance`와 `lastLoginDate` 필드가 변경된다.
3. 트랜잭션 2가 커밋될 때 JPA가 **스냅샷과 현재 상태를 비교**하여 변경된 필드를 자동으로 UPDATE 쿼리로 실행한다.

별도로 `userRepository.save(user)`를 호출하지 않아도 된다.

### Q15. 커넥션 풀 고갈이 왜 위험한가요? 단순히 로그인이 느려지는 것 아닌가요?

커넥션 풀은 **모든 API가 공유**하는 자원이다. 로그인 API가 커넥션을 오래 점유하면:

```
로그인 폭주 → 커넥션 풀 고갈 → 상품 조회 대기 → 주문 API 타임아웃 → 결제 실패 → 전체 서비스 장애
```

하나의 API 병목이 **전체 서비스를 마비**시키는 장애 전파 패턴이다. TransactionTemplate으로 커넥션 점유를 최소화하면, 로그인 트래픽이 급증해도 다른 API는 정상 동작한다.

---
---

# UserValidator 코드 한 줄 한 줄 분석

## 전체 코드

```java
// TODO: 글로벌 예외 처리 PR에서 커스텀 예외(BusinessException 등) 도입 예정
@Component
@RequiredArgsConstructor
public class UserValidator {

    private final UserRepository userRepository;

    public void validateSignUp(String email, String name) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다");
        }

        if (userRepository.existsByName(name)) {
            throw new IllegalArgumentException("이미 사용 중인 닉네임입니다");
        }
    }
}
```

---

## 1. 클래스 선언부

```java
@Component
@RequiredArgsConstructor
public class UserValidator {
```

### 왜 `@Component`이고 `@Service`가 아닌가?

- `UserValidator`는 **비즈니스 로직을 직접 수행하는 서비스가 아니라, 검증이라는 단일 책임을 가진 보조 컴포넌트**다.
- `@Service`는 비즈니스 흐름을 조율하는 클래스에 붙이고, `@Component`는 특정 기능을 담당하는 유틸리티성 클래스에 붙이는 것이 관례다.
- 기능적으로 `@Service`와 `@Component`는 동일하지만, **의미적 구분**이다.

### 왜 검증 로직을 별도 클래스로 분리했나?

- `UserService`에 private 메서드로 둘 수도 있지만, 검증 로직이 복잡해지면 서비스 클래스가 비대해진다.
- 별도 클래스로 분리하면 **단일 책임 원칙(SRP)**을 지킬 수 있고, 테스트도 독립적으로 가능하다.
- 향후 검증 규칙이 추가되더라도 `UserValidator`만 수정하면 된다.

---

## 2. 의존성 주입

```java
private final UserRepository userRepository;
```

- 중복 체크를 위해 DB 조회가 필요하므로 `UserRepository`를 주입받는다.
- `existsByEmail()`, `existsByName()`은 Spring Data JPA가 메서드 이름으로 쿼리를 자동 생성한다.

---

## 3. 회원가입 검증 — `validateSignUp()`

```java
public void validateSignUp(String email, String name) {
```

- 반환 타입이 `void`다. 검증 통과 시 아무것도 반환하지 않고, 실패 시 **예외를 던진다.**
- 파라미터로 `UserSignUpRequest`가 아닌 `String email, String name`을 받는다. DTO에 의존하지 않아 **재사용성이 높다.**

### 3-1. 이메일 중복 체크

```java
if (userRepository.existsByEmail(email)) {
    throw new IllegalArgumentException("이미 사용 중인 이메일입니다");
}
```

**`existsByEmail()`이 실행하는 쿼리:**

```sql
SELECT COUNT(*) > 0 FROM users WHERE email = ?
-- 또는 (Hibernate 최적화)
SELECT 1 FROM users WHERE email = ? LIMIT 1
```

- `findByEmail()` 대신 `existsByEmail()`을 사용한 이유: **엔티티 전체를 로딩할 필요 없이 존재 여부만 확인**하면 되기 때문이다. 메모리와 성능 면에서 더 효율적이다.

### 3-2. 닉네임 중복 체크

```java
if (userRepository.existsByName(name)) {
    throw new IllegalArgumentException("이미 사용 중인 닉네임입니다");
}
```

- 이메일과 동일한 패턴. `name` 컬럼도 unique 제약조건이 있으므로, 애플리케이션 레벨에서 먼저 체크한다.
- 이메일 → 닉네임 순서로 검증한다. **더 빈번하게 실패하는 검증을 먼저 배치**하면 불필요한 쿼리를 줄일 수 있지만, 현재는 두 검증 모두 빠른 쿼리이므로 순서의 영향은 미미하다.

---

## 4. 왜 `IllegalArgumentException`인가?

현재 `IllegalArgumentException`을 사용하는 이유:
- 글로벌 예외 처리가 아직 도입되지 않았기 때문이다.
- 추후 `DuplicateEmailException`, `DuplicateNicknameException` 같은 커스텀 예외로 교체 예정이다.
- 커스텀 예외를 사용하면 `GlobalExceptionHandler`에서 **예외 종류별로 다른 HTTP 상태 코드와 에러 코드**를 반환할 수 있다.

---

## 5. 예상 질문 & 답변

### Q1. 왜 검증을 별도 클래스로 분리했나요?

단일 책임 원칙(SRP)을 지키기 위해서다. `UserService`는 회원가입/로그인 플로우를 조율하는 역할이고, `UserValidator`는 검증이라는 단일 책임만 담당한다. 서비스가 비대해지는 것을 방지하고 테스트 작성이 용이해진다.

### Q2. `existsBy`와 `findBy`의 차이는? 왜 `existsBy`를 썼나요?

| 메서드 | 반환 타입 | 쿼리 | 용도 |
|---|---|---|---|
| `existsByEmail()` | `boolean` | `SELECT 1 ... LIMIT 1` | 존재 여부만 확인 |
| `findByEmail()` | `Optional<User>` | `SELECT * ...` | 엔티티 전체 로딩 |

중복 체크에서는 엔티티가 필요 없다. `existsBy`는 엔티티를 메모리에 올리지 않으므로 더 효율적이다.

### Q3. 이 검증만으로 동시성 문제를 완전히 방지할 수 있나요?

**아니다.** 두 요청이 동시에 `existsByEmail()`을 통과하고 둘 다 INSERT를 시도할 수 있다 (TOCTOU race condition). 이를 방지하기 위해:

1. **애플리케이션 레벨**: `UserValidator.validateSignUp()`으로 1차 방어
2. **DB 레벨**: `email`, `name` 컬럼에 unique 제약조건으로 2차 방어

`UserService`에서 검증과 저장을 **같은 트랜잭션 안에서** 실행하고, `GlobalExceptionHandler`에서 `DataIntegrityViolationException`을 처리하여 이중 방어한다.

### Q4. 왜 DTO가 아닌 `String email, String name`을 파라미터로 받나요?

- DTO에 의존하면 해당 DTO가 변경될 때 Validator도 수정해야 한다.
- 원시 타입으로 받으면 **다른 곳에서도 재사용 가능**하다 (예: OAuth 회원가입 시에도 이메일 중복 체크가 필요할 수 있다).
- 테스트 시에도 DTO를 만들 필요 없이 문자열만 넘기면 된다.

### Q5. 이메일 중복과 닉네임 중복을 한 번에 체크하지 않고 순차적으로 하는 이유는?

한 번에 체크하면 "무엇이 중복인지" 특정할 수 없다. 순차적으로 체크하면 **이메일이 중복인지, 닉네임이 중복인지 정확한 에러 메시지**를 반환할 수 있다. 사용자 경험(UX) 측면에서 중요하다.

---
---

# GoogleOAuthClient 코드 한 줄 한 줄 분석

## 전체 코드

```java
@Component
@RequiredArgsConstructor
public class GoogleOAuthClient {

    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";

    private final GoogleOAuthProperties properties;
    private final RestClient restClient = RestClient.create();

    public String getAuthorizationUrl() {
        return GOOGLE_AUTH_URL
                + "?client_id=" + properties.clientId()
                + "&redirect_uri=" + properties.redirectUri()
                + "&response_type=code"
                + "&scope=email%20profile";
    }

    public GoogleTokenResponse exchangeCodeForToken(String code) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("code", code);
        params.add("client_id", properties.clientId());
        params.add("client_secret", properties.clientSecret());
        params.add("redirect_uri", properties.redirectUri());
        params.add("grant_type", "authorization_code");

        return restClient.post()
                .uri(GOOGLE_TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(params)
                .retrieve()
                .body(GoogleTokenResponse.class);
    }

    public GoogleUserInfoResponse getUserInfo(String accessToken) {
        return restClient.get()
                .uri(GOOGLE_USERINFO_URL)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(GoogleUserInfoResponse.class);
    }
}
```

---

## 1. 클래스 선언부

```java
@Component
@RequiredArgsConstructor
public class GoogleOAuthClient {
```

### 왜 `@Component`인가?

- 이 클래스는 **외부 API(Google)와의 HTTP 통신만 담당하는 인프라 계층 컴포넌트**다.
- 비즈니스 로직(`@Service`)도 아니고, 데이터 접근(`@Repository`)도 아니다.
- Google API와의 통신이라는 **단일 책임**만 가진다.

### 왜 `OAuthService` 안에 넣지 않고 분리했나?

- `OAuthService`는 비즈니스 플로우(유저 조회/생성, 포인트 지급)를 담당한다.
- `GoogleOAuthClient`는 Google API 호출이라는 **기술적 관심사**를 담당한다.
- 분리하면 나중에 카카오, 네이버 등 다른 OAuth 프로바이더를 추가할 때 `KakaoOAuthClient`, `NaverOAuthClient`만 추가하면 된다. `OAuthService`의 비즈니스 로직은 변경하지 않아도 된다.

---

## 2. 상수 정의

```java
private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
private static final String GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";
```

| 상수 | 역할 | OAuth2 플로우 단계 |
|---|---|---|
| `GOOGLE_AUTH_URL` | 사용자를 구글 로그인 페이지로 보내는 URL | 1단계: 인증 요청 |
| `GOOGLE_TOKEN_URL` | authorization code를 access_token으로 교환하는 URL | 2단계: 토큰 교환 |
| `GOOGLE_USERINFO_URL` | access_token으로 유저 정보를 조회하는 URL | 3단계: 유저 정보 조회 |

이 URL들은 Google OAuth2 공식 문서에 정의된 엔드포인트다. 변경될 일이 거의 없으므로 `static final` 상수로 관리한다.

---

## 3. 의존성

```java
private final GoogleOAuthProperties properties;
private final RestClient restClient = RestClient.create();
```

### `GoogleOAuthProperties`

```java
@ConfigurationProperties(prefix = "google")
public record GoogleOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri
) {}
```

- `application.properties`의 `google.client-id`, `google.client-secret`, `google.redirect-uri` 값을 바인딩한다.
- 민감 정보(client-secret)를 코드에 하드코딩하지 않고 **설정 파일에서 관리**한다.
- record로 선언하여 불변 객체로 만들었다.

### `RestClient`

- Spring 6.1+에서 도입된 **동기식 HTTP 클라이언트**다.
- `RestTemplate`의 후속 API로, 더 직관적인 빌더 패턴을 제공한다.
- `RestClient.create()`로 기본 설정의 인스턴스를 생성한다.

**왜 `WebClient`(비동기)가 아닌 `RestClient`(동기)인가?**

- OAuth 플로우에서 토큰 교환 → 유저 정보 조회는 **순차적으로 실행**되어야 한다 (토큰 없이 유저 정보 조회 불가).
- 비동기의 이점이 없는 순차 호출에서는 동기식 `RestClient`가 더 간결하고 디버깅하기 쉽다.

---

## 4. 인증 URL 생성 — `getAuthorizationUrl()`

```java
public String getAuthorizationUrl() {
    return GOOGLE_AUTH_URL
            + "?client_id=" + properties.clientId()
            + "&redirect_uri=" + properties.redirectUri()
            + "&response_type=code"
            + "&scope=email%20profile";
}
```

프론트엔드가 이 URL로 사용자를 리다이렉트하면, 구글 로그인 페이지가 표시된다.

**각 파라미터 설명:**

| 파라미터 | 값 | 설명 |
|---|---|---|
| `client_id` | Google Cloud Console에서 발급 | 이 앱이 누구인지 식별 |
| `redirect_uri` | `http://localhost:8080/api/v1/oauth/google/callback` | 로그인 후 돌아올 URL |
| `response_type` | `code` | authorization code를 달라는 요청 (OAuth2 Authorization Code Grant) |
| `scope` | `email profile` | 이메일과 프로필 정보에 접근하겠다는 권한 요청 |

**`%20`은 무엇인가?**

URL에서 공백을 표현하는 인코딩이다. `email profile` → `email%20profile`.

---

## 5. 토큰 교환 — `exchangeCodeForToken()`

```java
public GoogleTokenResponse exchangeCodeForToken(String code) {
    MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
    params.add("code", code);
    params.add("client_id", properties.clientId());
    params.add("client_secret", properties.clientSecret());
    params.add("redirect_uri", properties.redirectUri());
    params.add("grant_type", "authorization_code");
```

구글에서 받은 authorization code를 access_token으로 교환한다.

**각 파라미터 설명:**

| 파라미터 | 설명 |
|---|---|
| `code` | 구글 로그인 후 콜백으로 받은 authorization code |
| `client_id` | 앱 식별자 |
| `client_secret` | 앱 비밀키 (서버만 알아야 함) |
| `redirect_uri` | 인증 요청 시 사용한 것과 **정확히 동일**해야 함 |
| `grant_type` | `authorization_code` — OAuth2 표준 그랜트 타입 |

**왜 `MultiValueMap`인가?**

`application/x-www-form-urlencoded` 형식은 `key=value&key=value` 형태로 전송된다.
`MultiValueMap`은 Spring에서 이 형식을 표현하는 표준 자료구조다.
`Map<String, String>`을 사용하면 `RestClient`가 JSON으로 직렬화해버린다.

```java
    return restClient.post()
            .uri(GOOGLE_TOKEN_URL)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(params)
            .retrieve()
            .body(GoogleTokenResponse.class);
}
```

| 체인 메서드 | 설명 |
|---|---|
| `.post()` | HTTP POST 요청 |
| `.uri(GOOGLE_TOKEN_URL)` | `https://oauth2.googleapis.com/token`으로 요청 |
| `.contentType(APPLICATION_FORM_URLENCODED)` | `Content-Type: application/x-www-form-urlencoded` |
| `.body(params)` | 파라미터를 요청 본문에 포함 |
| `.retrieve()` | 요청 실행 |
| `.body(GoogleTokenResponse.class)` | 응답 JSON을 `GoogleTokenResponse`로 역직렬화 |

**`GoogleTokenResponse`:**

```java
public record GoogleTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Integer expiresIn
) {}
```

- `@JsonProperty("access_token")`으로 JSON의 snake_case를 Java의 camelCase에 매핑한다.
- Google 응답 예시: `{"access_token": "ya29.xxx", "token_type": "Bearer", "expires_in": 3599}`

---

## 6. 유저 정보 조회 — `getUserInfo()`

```java
public GoogleUserInfoResponse getUserInfo(String accessToken) {
    return restClient.get()
            .uri(GOOGLE_USERINFO_URL)
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .body(GoogleUserInfoResponse.class);
}
```

| 체인 메서드 | 설명 |
|---|---|
| `.get()` | HTTP GET 요청 |
| `.uri(GOOGLE_USERINFO_URL)` | `https://www.googleapis.com/oauth2/v3/userinfo` |
| `.header("Authorization", "Bearer " + accessToken)` | Bearer 토큰 인증 헤더 |
| `.retrieve()` | 요청 실행 |
| `.body(GoogleUserInfoResponse.class)` | 응답을 역직렬화 |

**`GoogleUserInfoResponse`:**

```java
public record GoogleUserInfoResponse(
        String sub,      // 구글 고유 유저 ID
        String name,     // 이름
        String email     // 이메일
) {}
```

- `sub`는 Google에서 유저를 식별하는 **고유 ID**. 이메일이 변경되어도 sub는 변하지 않는다.
- 이 값을 `User.providerUserId`에 저장한다.

---

## 7. 전체 OAuth2 플로우에서 이 클래스의 위치

```
1. getAuthorizationUrl()
   → 프론트엔드가 구글 로그인 페이지로 리다이렉트

2. 사용자가 구글에서 로그인 + 동의

3. 구글이 callback URL로 authorization code 전달

4. exchangeCodeForToken(code)
   → code를 access_token으로 교환

5. getUserInfo(accessToken)
   → access_token으로 유저 이메일/이름 조회

6. OAuthService에서 DB 조회/저장 (이 클래스의 범위 밖)
```

---

## 8. 예상 질문 & 답변

### Q1. 왜 Spring Security의 OAuth2 Client를 사용하지 않았나요?

Spring Security를 사용하지 않는 프로젝트이다. `spring-boot-starter-oauth2-client`를 추가하면 Spring Security의 자동 설정이 함께 적용되어 불필요한 보안 설정을 해야 한다. OAuth2 플로우 자체는 HTTP 요청 3번(인증 URL → 토큰 교환 → 유저 정보)이므로 `RestClient`로 직접 구현해도 충분히 간결하다.

### Q2. `RestClient` vs `RestTemplate` vs `WebClient` 중 왜 `RestClient`인가?

| 클라이언트 | 동기/비동기 | Spring 버전 | 특징 |
|---|---|---|---|
| `RestTemplate` | 동기 | 3.0+ | 레거시, 유지보수 모드 |
| `WebClient` | 비동기 | 5.0+ | 리액티브, webflux 의존성 필요 |
| `RestClient` | 동기 | 6.1+ | **RestTemplate의 후속**, 빌더 패턴 |

OAuth 토큰 교환 → 유저 정보 조회는 순차 호출이므로 비동기의 이점이 없다. Spring Boot 4 기준 `RestClient`가 권장되는 동기식 HTTP 클라이언트다.

### Q3. `client_secret`이 코드에 노출되면 위험하지 않나요?

코드에 하드코딩하지 않고 `GoogleOAuthProperties`를 통해 `application.properties`에서 읽는다. 프로덕션에서는:
- 환경 변수: `GOOGLE_CLIENT_SECRET=xxx`
- Spring Cloud Config / AWS Secrets Manager 등 시크릿 관리 서비스

`.gitignore`에 `application.properties`를 추가하거나, `application-secret.properties`로 분리하여 민감 정보가 Git에 올라가지 않도록 해야 한다.

### Q4. Google API 호출이 실패하면 어떻게 되나요?

현재는 `RestClient`가 4xx/5xx 응답을 받으면 `RestClientException`을 던지고, Spring이 500 에러로 응답한다. 글로벌 예외 처리 PR에서 개선 예정:
- 잘못된 code → 400 에러 + "인증 코드가 유효하지 않습니다"
- Google 서버 장애 → 503 에러 + "외부 인증 서비스에 연결할 수 없습니다"

### Q5. authorization code는 왜 1회용인가요?

OAuth2 보안 스펙이다. code가 탈취되더라도 1회 사용 후 무효화되므로 **재사용 공격(replay attack)**을 방지한다. 또한 code의 유효 시간이 5~10분으로 짧아 탈취 후 사용 가능한 시간도 제한된다.

### Q6. `redirect_uri`를 토큰 교환에서도 보내는 이유는?

OAuth2 스펙상 토큰 교환 요청의 `redirect_uri`는 **인증 요청 시 사용한 값과 정확히 일치**해야 한다. 이는 authorization code가 특정 redirect_uri로 발급되었음을 검증하기 위한 보안 장치다. 값이 다르면 Google이 요청을 거부한다.

### Q7. `scope`에 `email profile`만 요청한 이유는?

회원가입/로그인에 필요한 최소한의 정보만 요청한다:
- `email`: 유저 식별 (unique key)
- `profile`: 이름 (닉네임으로 사용)

**최소 권한 원칙(Principle of Least Privilege)**을 따른다. 불필요한 scope를 요청하면 사용자 동의 화면에서 거부감을 줄 수 있다.

---
---

# OAuthService 코드 한 줄 한 줄 분석

## 전체 코드

```java
@Service
@RequiredArgsConstructor
public class OAuthService {

    private final GoogleOAuthClient googleOAuthClient;
    private final UserRepository userRepository;

    public String getGoogleAuthorizationUrl() {
        return googleOAuthClient.getAuthorizationUrl();
    }

    // TODO: 글로벌 예외 처리 PR에서 커스텀 예외 도입 예정
    public OAuthLoginResponse googleLogin(String code) {
        GoogleTokenResponse tokenResponse = googleOAuthClient.exchangeCodeForToken(code);
        Objects.requireNonNull(tokenResponse, "Google 토큰 응답이 null입니다");

        GoogleUserInfoResponse userInfo = googleOAuthClient.getUserInfo(tokenResponse.accessToken());
        Objects.requireNonNull(userInfo, "Google 유저 정보 응답이 null입니다");

        return findOrCreateUser(userInfo);
    }

    @Transactional
    protected OAuthLoginResponse findOrCreateUser(GoogleUserInfoResponse userInfo) {
        Optional<User> existingUser = userRepository.findByEmail(userInfo.email());

        if (existingUser.isPresent()) {
            User user = existingUser.get();

            if (user.getProvider() == null) {
                throw new IllegalArgumentException("이미 일반 회원가입으로 등록된 이메일입니다");
            }

            user.rewardLoginPoint(LocalDate.now());
            return OAuthLoginResponse.from(user, false);
        }

        User newUser = User.createOAuth(
                userInfo.name(),
                userInfo.email(),
                Provider.GOOGLE,
                userInfo.sub()
        );
        newUser.rewardLoginPoint(LocalDate.now());
        User savedUser = userRepository.save(newUser);

        return OAuthLoginResponse.from(savedUser, true);
    }
}
```

---

## 1. 클래스 선언부와 의존성

```java
@Service
@RequiredArgsConstructor
public class OAuthService {

    private final GoogleOAuthClient googleOAuthClient;
    private final UserRepository userRepository;
```

| 필드 | 역할 |
|---|---|
| `googleOAuthClient` | Google OAuth API 호출 (토큰 교환, 유저 정보 조회) |
| `userRepository` | DB 접근 (유저 조회/저장) |

### 왜 `UserService`에 넣지 않고 `OAuthService`를 분리했나?

- `UserService`는 **자체 인증**(이메일/비밀번호)을 담당한다.
- `OAuthService`는 **외부 인증**(Google OAuth)을 담당한다.
- 관심사가 다르다. 합치면 하나의 서비스가 자체 인증 + 외부 OAuth + 추후 카카오/네이버까지 담당하여 비대해진다.

### 왜 `PasswordEncoder`가 없나?

OAuth 로그인은 **비밀번호가 없다.** Google이 인증을 대신 처리하므로 BCrypt가 불필요하다. 따라서 커넥션 풀 최적화를 위한 `TransactionTemplate`도 필요하지 않다.

---

## 2. 인증 URL 위임 — `getGoogleAuthorizationUrl()`

```java
public String getGoogleAuthorizationUrl() {
    return googleOAuthClient.getAuthorizationUrl();
}
```

- `GoogleOAuthClient`에 단순 위임한다.
- 컨트롤러가 `GoogleOAuthClient`를 직접 호출하지 않고 서비스를 경유하는 이유: **컨트롤러 → 서비스 → 인프라** 계층 구조를 유지하기 위해서다.

---

## 3. 구글 로그인 — `googleLogin()`

### 3-1. 토큰 교환 (트랜잭션 밖)

```java
GoogleTokenResponse tokenResponse = googleOAuthClient.exchangeCodeForToken(code);
Objects.requireNonNull(tokenResponse, "Google 토큰 응답이 null입니다");
```

- authorization code를 Google에 보내서 access_token을 받는다.
- **외부 HTTP 통신**이므로 수백 ms가 걸릴 수 있다. 이 시점에는 DB 트랜잭션이 열려있지 않다.
- `Objects.requireNonNull()`로 null 방어: `RestClient`가 응답 역직렬화에 실패하면 null을 반환할 수 있다.

### 3-2. 유저 정보 조회 (트랜잭션 밖)

```java
GoogleUserInfoResponse userInfo = googleOAuthClient.getUserInfo(tokenResponse.accessToken());
Objects.requireNonNull(userInfo, "Google 유저 정보 응답이 null입니다");
```

- access_token으로 Google에서 유저 이메일/이름/고유ID를 가져온다.
- 역시 외부 HTTP 통신. 트랜잭션 밖이므로 커넥션 점유 없음.

### 3-3. DB 조회/저장 위임

```java
return findOrCreateUser(userInfo);
```

- 외부 API 호출이 모두 끝난 후에야 DB 작업(`findOrCreateUser`)을 실행한다.
- **외부 통신(느림)과 DB 작업(빠름)을 분리**한 설계다.

**이것이 `UserService`의 TransactionTemplate 패턴과 동일한 원리다:**

```
UserService:     BCrypt(~100ms, 트랜잭션 밖) → DB 작업(트랜잭션 안)
OAuthService:    Google API(~수백ms, 트랜잭션 밖) → DB 작업(트랜잭션 안)
```

둘 다 **느린 작업을 트랜잭션 밖에서 실행하여 커넥션 점유를 최소화**한다.

---

## 4. 유저 조회 또는 생성 — `findOrCreateUser()`

```java
@Transactional
protected OAuthLoginResponse findOrCreateUser(GoogleUserInfoResponse userInfo) {
```

### 왜 `@Transactional`인가? `TransactionTemplate`이 아니고?

OAuth 로그인에는 BCrypt 같은 CPU 작업이 없다. 이 메서드 안의 코드는 **전부 DB 작업**이다. `@Transactional`을 써도 커넥션이 불필요하게 점유되는 구간이 없으므로, 더 간결한 `@Transactional`을 사용한다.

### 왜 `protected`인가?

`@Transactional`은 **Spring AOP 프록시**를 통해 동작한다. 같은 클래스 내에서 `this.findOrCreateUser()`로 호출하면 프록시를 거치지 않아 **트랜잭션이 적용되지 않는다.**

하지만 현재 코드에서는 `googleLogin()` → `findOrCreateUser()` 호출이 **같은 클래스 내 호출**이므로, 실제로는 `@Transactional`이 동작하지 않을 수 있다. 이를 해결하려면:
1. `findOrCreateUser()`를 별도 클래스로 분리하거나
2. `TransactionTemplate`으로 변경하는 것이 정확하다.

현재는 Spring이 CGLIB 프록시를 사용하고, `protected` 메서드도 프록시가 가능하지만, **self-invocation 문제는 여전히 존재**한다. 추후 리팩토링 대상이다.

### 4-1. 기존 유저 조회

```java
Optional<User> existingUser = userRepository.findByEmail(userInfo.email());
```

- 구글 이메일로 DB에서 유저를 찾는다.
- `Optional`로 반환하여 null 체크를 강제한다.

### 4-2. 기존 유저가 있는 경우 — 재로그인

```java
if (existingUser.isPresent()) {
    User user = existingUser.get();

    if (user.getProvider() == null) {
        throw new IllegalArgumentException("이미 일반 회원가입으로 등록된 이메일입니다");
    }

    user.rewardLoginPoint(LocalDate.now());
    return OAuthLoginResponse.from(user, false);
}
```

**`user.getProvider() == null` 체크는 왜 하는가?**

같은 이메일로 일반 회원가입한 유저가 있을 수 있다:
- 일반 회원가입 유저: `provider = null`, `password = 해시값`
- OAuth 유저: `provider = GOOGLE`, `password = null`

일반 회원가입 유저가 구글 로그인을 시도하면 **계정 충돌**이 발생한다. 이를 방지하기 위해 예외를 던진다.

**`rewardLoginPoint(LocalDate.now())`:**

- 기존 OAuth 유저의 재로그인이므로 일일 로그인 포인트를 지급한다.
- `@Transactional` 안이므로 더티 체킹에 의해 자동 UPDATE.

**`OAuthLoginResponse.from(user, false)`:**

- `newUser: false` — 신규 유저가 아님을 프론트엔드에 알린다.

### 4-3. 신규 유저 — 회원가입

```java
User newUser = User.createOAuth(
        userInfo.name(),
        userInfo.email(),
        Provider.GOOGLE,
        userInfo.sub()
);
newUser.rewardLoginPoint(LocalDate.now());
User savedUser = userRepository.save(newUser);

return OAuthLoginResponse.from(savedUser, true);
```

**`User.createOAuth()`:**

```java
public static User createOAuth(String name, String email, Provider provider, String providerUserId) {
    return User.builder()
            .name(name)
            .email(email)
            .provider(provider)
            .providerUserId(providerUserId)
            .build();  // password = null, role = USER, grade = BRONZE
}
```

- 일반 `User.create()`와 달리 **password 없이** 생성한다.
- `provider = GOOGLE`, `providerUserId = sub` (구글 고유 ID)

**`newUser.rewardLoginPoint(LocalDate.now())`:**

- 신규 유저의 첫 로그인이므로 1000P 지급.
- `lastLoginDate`가 null이므로 `FIRST_LOGIN_POINT`(1000) 적용.

**`userRepository.save(newUser)`:**

- JPA INSERT 실행. `save()` 후 `userId`가 자동 채번된다 (`GenerationType.IDENTITY`).

**`OAuthLoginResponse.from(savedUser, true)`:**

- `newUser: true` — 프론트엔드가 이 값을 보고 신규 유저 환영 UI 등을 표시할 수 있다.

---

## 5. 전체 플로우 타임라인

```
시간 →

0ms        ~200ms      ~400ms     ~403ms        ~406ms
 |           |           |          |              |
 | token교환 | 유저정보   |◄─ TX ──►|              |
 | (Google)  | (Google)  | SELECT   |              |
 | 커넥션 없음| 커넥션 없음| + INSERT |              |
 |           |           | 커넥션점유|              |

총 커넥션 점유: ~3-6ms
총 응답 시간: ~400ms+ (대부분 Google API 호출 시간)
```

**핵심:** Google API 호출(~400ms)이 트랜잭션 밖에서 실행되므로, 커넥션은 DB 작업(~3-6ms) 동안만 점유된다.

---

## 6. 예상 질문 & 답변

### Q1. `googleLogin()`에서 `findOrCreateUser()`를 같은 클래스에서 호출하면 `@Transactional`이 동작하나요?

**Spring AOP의 self-invocation 문제다.** 같은 클래스 내에서 `this.findOrCreateUser()`를 호출하면 프록시를 거치지 않으므로 `@Transactional`이 적용되지 않을 수 있다.

해결 방법:
1. `findOrCreateUser()`를 별도 클래스로 분리
2. `TransactionTemplate`으로 변경
3. `self` 주입 (권장하지 않음)

현재 코드는 이 문제를 인지하고 있으며, 추후 리팩토링 대상이다.

### Q2. `OAuthService`에는 왜 `TransactionTemplate`을 안 쓰나요?

OAuth 로그인에는 BCrypt 같은 CPU 작업이 없다. `findOrCreateUser()` 안의 코드는 전부 DB 작업이므로 `@Transactional`을 써도 커넥션이 불필요하게 점유되는 구간이 없다. Google API 호출은 `googleLogin()`에서 트랜잭션 밖에서 이미 완료된다.

### Q3. 일반 회원가입 유저와 OAuth 유저의 이메일이 충돌하면 어떻게 하나요?

현재는 예외를 던진다. 프로덕션에서는 두 가지 전략이 있다:

| 전략 | 설명 |
|---|---|
| **차단** (현재) | "이미 일반 회원가입으로 등록된 이메일입니다" 에러 |
| **연동** | 비밀번호 확인 후 기존 계정에 OAuth 연결 (계정 연동) |

계정 연동은 보안적으로 더 복잡하므로 (인증되지 않은 사용자가 다른 사람의 계정에 OAuth를 연결할 위험), 현재는 차단 방식을 사용한다.

### Q4. `newUser` 플래그는 왜 필요한가요?

프론트엔드가 신규 유저와 기존 유저를 구분하여 다른 UX를 제공할 수 있다:
- `newUser: true` → 환영 페이지, 튜토리얼, 추가 정보 입력 요청
- `newUser: false` → 메인 페이지로 바로 이동

### Q5. Google에서 받은 `sub` 값은 왜 저장하나요?

`sub`는 Google에서 유저를 식별하는 **불변 고유 ID**다. 이메일은 사용자가 변경할 수 있지만 `sub`는 변하지 않는다. 향후 이메일 변경된 유저를 식별하거나, Google 계정 연동 해제/재연동 시 사용할 수 있다.

### Q6. OAuth에서도 포인트를 지급하는 이유는?

일반 로그인과 OAuth 로그인은 **로그인 방식만 다를 뿐 같은 유저 활동**이다. 로그인 방식에 따라 포인트 지급 여부가 달라지면 유저 입장에서 불공평하다. 동일한 정책(첫 로그인 1000P, 일일 10P)을 적용한다.
