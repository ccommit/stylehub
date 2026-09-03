# Google OAuth 로그인 — Self-Invocation 버그 발견 및 해결

## 배경

Google OAuth 로그인 구현 후 코드 리뷰 과정에서, `OAuthService`의 트랜잭션이 정상 동작하지 않는 구조적 문제를 발견했다.

### 문제의 코드

```java
@Service
public class OAuthService {

    public OAuthLoginResponse googleLogin(String code) {
        // Google API 호출 (트랜잭션 밖) ✅
        GoogleTokenResponse tokenResponse = googleOAuthClient.exchangeCodeForToken(code);
        GoogleUserInfoResponse userInfo = googleOAuthClient.getUserInfo(tokenResponse.accessToken());

        return findOrCreateUser(userInfo);  // ← 같은 클래스 내부 호출
    }

    @Transactional  // ← 동작하지 않음
    protected OAuthLoginResponse findOrCreateUser(GoogleUserInfoResponse userInfo) {
        Optional<User> existingUser = userRepository.findByEmail(userInfo.email());

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            user.rewardLoginPoint(LocalDate.now());  // 더티 체킹으로 UPDATE 기대
            return OAuthLoginResponse.from(user, false);
        }

        // 신규 유저 생성 ...
    }
}
```

겉보기에는 문제없어 보이지만, **`@Transactional`이 실제로는 동작하지 않는** 코드였다.

---

## 과정

### 1. 문제 인식 — @Transactional은 AOP 프록시로 동작한다

Spring의 `@Transactional`은 **AOP 프록시**를 통해 트랜잭션을 관리한다.

```
[외부에서 호출하는 경우]
Controller → OAuthService$$Proxy.findOrCreateUser()
                    ↓
            프록시가 트랜잭션 시작
                    ↓
            실제 메서드 실행
                    ↓
            프록시가 트랜잭션 커밋  ✅ 정상 동작
```

```
[같은 클래스 내부에서 호출하는 경우 — 현재 코드]
googleLogin() → this.findOrCreateUser()
                    ↓
            프록시를 거치지 않고 직접 호출
                    ↓
            트랜잭션 없이 실행  ❌ 문제 발생
```

`googleLogin()`에서 `findOrCreateUser()`를 호출하면 `this.findOrCreateUser()`로 실행된다.
이는 프록시 객체가 아닌 **실제 객체의 메서드를 직접 호출**하는 것이므로,
`@Transactional` 어노테이션이 무시된다. 이것이 **self-invocation 문제**다.

### 2. 영향 분석 — 어떤 버그가 발생하는가?

트랜잭션이 없으면 **JPA 더티 체킹이 동작하지 않는다.**

| 시나리오 | 기대 동작 | 실제 동작 |
|---|---|---|
| 신규 유저 생성 | `save()` 호출 → INSERT | **정상** (`save()` 내부에 자체 `@Transactional` 있음) |
| 기존 유저 재로그인 | 더티 체킹 → UPDATE | **버그: UPDATE 안 됨** |

기존 OAuth 유저가 재로그인하면:

```java
User user = existingUser.get();
user.rewardLoginPoint(LocalDate.now());  // pointBalance, lastLoginDate 변경
return OAuthLoginResponse.from(user, false);
// ← 트랜잭션이 없으므로 커밋 시점이 없음 → UPDATE 쿼리 실행 안 됨
```

**결과: 기존 OAuth 유저가 재로그인해도 포인트가 쌓이지 않고, lastLoginDate도 갱신되지 않는다.**

신규 유저는 `userRepository.save()`를 명시적으로 호출하기 때문에 문제없이 동작했다.
이 때문에 신규 가입 테스트만으로는 버그를 발견하기 어려웠다.

### 3. 해결 방법 검토

| 방법 | 설명 | 채택 여부 |
|---|---|---|
| 별도 클래스로 분리 | `findOrCreateUser()`를 다른 클래스로 이동 → 외부 호출이 되어 프록시 동작 | ❌ 클래스가 불필요하게 늘어남 |
| `self` 주입 | 자기 자신을 주입받아 프록시 경유 호출 | ❌ 안티패턴, 순환 참조 위험 |
| **TransactionTemplate** | 프로그래밍 방식으로 트랜잭션 직접 관리 | ✅ **채택** |

`TransactionTemplate`은 프록시가 아니라 **코드에서 직접 트랜잭션을 열고 닫는** 방식이므로,
같은 클래스 내부 호출이든 외부 호출이든 상관없이 트랜잭션이 보장된다.

또한 이 프로젝트의 `UserService`에서도 이미 `TransactionTemplate`을 사용하고 있으므로,
일관성 측면에서도 `TransactionTemplate`이 적합했다.

---

## 결과

### 수정된 코드

```java
@Service
@RequiredArgsConstructor
public class OAuthService {

    private final GoogleOAuthClient googleOAuthClient;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    public OAuthLoginResponse googleLogin(String code) {
        // 1. Google API 호출 (외부 HTTP 통신 — 트랜잭션 밖)
        GoogleTokenResponse tokenResponse = googleOAuthClient.exchangeCodeForToken(code);
        Objects.requireNonNull(tokenResponse, "Google 토큰 응답이 null입니다");

        GoogleUserInfoResponse userInfo = googleOAuthClient.getUserInfo(tokenResponse.accessToken());
        Objects.requireNonNull(userInfo, "Google 유저 정보 응답이 null입니다");

        // 2. DB 조회/저장 (트랜잭션 안)
        return Objects.requireNonNull(
                transactionTemplate.execute(status -> {
                    Optional<User> existingUser = userRepository.findByEmail(userInfo.email());

                    if (existingUser.isPresent()) {
                        User user = existingUser.get();

                        if (user.getProvider() == null) {
                            throw new IllegalArgumentException("이미 일반 회원가입으로 등록된 이메일입니다");
                        }

                        user.rewardLoginPoint(LocalDate.now());  // 더티 체킹 정상 동작 ✅
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
                })
        );
    }
}
```

### 변경 전/후 비교

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 트랜잭션 방식 | `@Transactional` (self-invocation으로 미동작) | `TransactionTemplate` (직접 트랜잭션 관리) |
| 기존 유저 재로그인 포인트 | DB에 반영 안 됨 ❌ | 정상 반영 ✅ |
| 기존 유저 lastLoginDate | 갱신 안 됨 ❌ | 정상 갱신 ✅ |
| 신규 유저 생성 | 정상 (`save()` 자체 트랜잭션) | 정상 |
| Google API 호출 | 트랜잭션 밖 | 트랜잭션 밖 (동일) |
| 커넥션 점유 | 의도상 최소화였으나 트랜잭션 자체가 미동작 | 실제로 DB 작업 구간만 점유 |

### 커넥션 점유 타임라인

```
Google API 호출 (~수백ms)          DB 작업 (~3-6ms)
|◄── 커넥션 없음 ──►|              |◄── 커넥션 점유 ──►|
                                   transactionTemplate.execute()
                                   SELECT + UPDATE or INSERT
```

---

## 해결 과정에서 얻은 교훈

### 1. `@Transactional`은 만능이 아니다

`@Transactional`을 붙였다고 항상 트랜잭션이 보장되지 않는다.
AOP 프록시의 동작 원리를 이해하지 못하면, **코드상으로는 완벽해 보이지만 실제로는 트랜잭션이 없는** 상황이 발생한다.

### 2. Self-invocation은 흔한 실수다

같은 클래스 내에서 `@Transactional` 메서드를 호출하는 패턴은 매우 자연스럽게 작성된다.
IDE에서 경고를 주지 않는 경우도 많아, **코드 리뷰나 테스트로만 발견할 수 있다.**

### 3. TransactionTemplate은 안전한 대안이다

`TransactionTemplate`은 프록시에 의존하지 않고 **코드에서 명시적으로 트랜잭션 범위를 제어**한다.
self-invocation 문제가 원천적으로 발생하지 않으며, 트랜잭션 범위가 코드에 명확히 드러난다.

### 4. 신규 생성만 테스트하면 버그를 놓친다

이 버그는 **기존 유저 재로그인** 시에만 발생했다.
신규 유저 생성은 `save()`를 명시적으로 호출하므로 문제없이 동작했다.
모든 분기(신규/기존)를 테스트하지 않으면 더티 체킹 관련 버그를 놓칠 수 있다.

---

## 마무리

이번 버그는 **코드가 정상적으로 컴파일되고, 일부 시나리오에서는 정상 동작하지만, 특정 조건에서만 실패하는** 유형이었다. Spring AOP의 프록시 동작 원리를 이해하고, `@Transactional`의 한계를 인지하고 있어야 발견할 수 있는 문제다.

`TransactionTemplate`으로의 전환은 단순히 버그 수정을 넘어, 프로젝트 전체의 트랜잭션 관리 방식을 **프록시 의존에서 명시적 제어로 통일**하는 결과를 가져왔다. `UserService`와 `OAuthService` 모두 `TransactionTemplate`을 사용하여 일관된 트랜잭션 관리 패턴을 유지한다.
