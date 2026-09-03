# @Transactional의 함정 — Self-Invocation 버그를 통해 본 Spring AOP 프록시의 본질

## 왜 이 버그가 신뢰할 수 없는 추상화의 표본 사례인가

Spring의 `@Transactional`은 어노테이션 한 줄로 트랜잭션을 보장하는 추상화처럼 보인다. 그러나 이 추상화는 **Spring AOP 프록시**라는 구체 메커니즘 위에서 동작하며, 프록시가 호출 경로에서 우회되는 순간 어노테이션은 침묵 속에 무시된다. 컴파일러는 경고하지 않고, IDE는 표시하지 않고, 일부 시나리오는 정상 동작하기까지 한다.

이 글의 사례는 **OAuth 재로그인 시 기존 유저의 포인트가 DB에 반영되지 않는 버그**다. 표면적으로는 단일 버그지만, 본질적으로는 **"추상화를 그 메커니즘 이해 없이 사용하면 어떻게 무너지는가"** 의 표본 케이스다. 이 글은 그 추적·진단·해결 과정과, 같은 함정을 다시 만나지 않기 위해 프로젝트 전반의 트랜잭션 관리 방식을 어떻게 표준화했는지의 기록이다.

## 1. 증상 — "일부는 되고 일부는 안 되는" 가장 까다로운 패턴

```java
public OAuthLoginResponse googleLogin(String code) {
    // Google API 호출 ...
    return findOrCreateUser(userInfo);
}

@Transactional
protected OAuthLoginResponse findOrCreateUser(GoogleUserInfoResponse userInfo) {
    // DB 조회/저장 ...
}
```

`@Transactional` 어노테이션은 정확한 위치에 붙어 있고, 컴파일 에러도 없으며, **신규 유저 가입은 정상 동작**한다. 그러나 **기존 유저의 재로그인 시 포인트와 lastLoginDate가 DB에 반영되지 않는다**.

이 패턴 — *"전부 작동하지 않는 것"이 아니라 "일부 시나리오만 무너지는 것"* — 이 디버깅에서 가장 까다로운 형태다. 부분적 작동이 "전체가 작동한다"는 잘못된 확신을 만들기 때문이다. 이 버그를 발견하려면 **"기존 유저의 재로그인 + DB 값 검증"** 이라는 특정 시나리오를 반드시 포함한 테스트가 필요하다.

---

## 1.1. 두 시나리오의 코드 차이 — 단서

로그인 시 포인트를 지급하는 로직은 이렇다.

```java
@Transactional
protected OAuthLoginResponse findOrCreateUser(GoogleUserInfoResponse userInfo) {
    Optional<User> existingUser = userRepository.findByEmail(userInfo.email());

    if (existingUser.isPresent()) {
        // 기존 유저: 더티 체킹으로 UPDATE 기대
        User user = existingUser.get();
        user.rewardLoginPoint(LocalDate.now());
        return OAuthLoginResponse.from(user, false);
    }

    // 신규 유저: save() 명시 호출
    User newUser = User.createOAuth(...);
    newUser.rewardLoginPoint(LocalDate.now());
    userRepository.save(newUser);  // INSERT 실행
    return OAuthLoginResponse.from(savedUser, true);
}
```

- **신규 유저**: `save()`를 명시적으로 호출 → INSERT 실행 → **정상**
- **기존 유저**: 엔티티 필드만 변경 → 더티 체킹으로 UPDATE 기대 → **DB에 반영 안 됨**

이 비대칭이 결정적 단서다. 두 케이스의 차이는 단 하나 — **DB 변경이 명시적 메서드 호출(save)인가, 더티 체킹(트랜잭션 종료 시 자동 UPDATE)인가**. 신규 유저는 `save()` 자체에 트랜잭션이 있어 동작했지만, 기존 유저는 외부 트랜잭션의 더티 체킹에 의존했다. 즉 **외부 트랜잭션이 무효화되면 기존 유저만 무너지는 패턴**이다.

이 단서가 가설을 지목한다 — `findOrCreateUser`의 `@Transactional`이 실제로는 동작하지 않고 있다.

## 2. 원인 — Self-Invocation, AOP 프록시의 본질적 한계

`@Transactional`은 Spring AOP **프록시**를 통해 동작한다. 이 메커니즘을 이해하면 버그가 자명해진다.

Spring이 `OAuthService` 빈을 생성할 때, 실제 객체를 감싸는 **프록시 객체**를 만든다.
외부에서 `@Transactional` 메서드를 호출하면 프록시가 가로채서 트랜잭션을 시작한다.

```
[외부에서 호출]
Controller → OAuthService$$Proxy.findOrCreateUser()
                 │
                 ├─ 프록시가 트랜잭션 시작
                 ├─ 실제 메서드 실행
                 └─ 프록시가 트랜잭션 커밋 ✅
```

**하지만 같은 클래스 내부에서 호출하면 프록시를 거치지 않는다.**

```
[같은 클래스 내부 호출 — 현재 코드]
googleLogin() → this.findOrCreateUser()
                 │
                 └─ 프록시를 거치지 않고 직접 호출
                    → @Transactional 무시 ❌
                    → 트랜잭션 없음
                    → 더티 체킹 불가
```

`googleLogin()`이 `findOrCreateUser()`를 호출할 때, Java 내부적으로 `this.findOrCreateUser()`가 실행된다. `this`는 프록시가 아닌 **실제 객체**를 가리키므로, `@Transactional` 어노테이션이 완전히 무시된다.

이것이 **self-invocation 문제**다. 그리고 이 문제의 본질은 **추상화의 메커니즘이 가시적 코드 표면에 드러나지 않는다는 점**이다 — `@Transactional` 어노테이션을 보고 트랜잭션 동작을 가정하지만, 실제 동작 여부는 호출 경로(외부 호출인가 내부 호출인가)에 의해 결정된다. 어노테이션은 의도를 표현할 뿐 보장하지 않는다.

## 3. 비대칭의 설명 — 왜 신규 유저만 정상이었는가

`save()` 메서드의 내부를 보면:

```java
// SimpleJpaRepository (Spring Data JPA 내부)
@Transactional
public <S extends T> S save(S entity) {
    // ...
}
```

`save()`에는 자체적으로 `@Transactional`이 붙어있다.
그리고 이 호출은 **외부 호출**(OAuthService → JpaRepository)이므로 프록시가 정상 동작한다.

| 시나리오 | 트랜잭션 | 이유 |
|---|---|---|
| 신규 유저 → `save()` | ✅ 동작 | `save()` 자체에 `@Transactional`, OAuthService에서 JpaRepository로 외부 호출 |
| 기존 유저 → 더티 체킹 | ❌ 미동작 | `findOrCreateUser()`의 `@Transactional`이 self-invocation으로 무시 |

이 표가 "일부는 되고 일부는 안 되는" 비대칭을 완전히 설명한다. 신규 유저는 `save()` 호출이 OAuthService → JpaRepository로 **클래스 경계를 넘는 외부 호출**이므로 프록시가 정상 동작한다. 기존 유저는 OAuthService 내부의 self-invocation에만 의존하므로 프록시가 우회된다.

이 분석이 의미하는 일반 원리: **추상화(@Transactional)의 동작 여부는 코드가 아니라 호출 토폴로지에 의해 결정된다.** 따라서 추상화가 동작하는지 검증하려면 단순히 "어노테이션이 붙어 있는가"가 아니라 "이 메서드가 어떤 경로로 호출되는가"를 확인해야 한다.

## 4. 해결 후보 비교 — TransactionTemplate을 선택한 근거

| 후보 | 메커니즘 | 평가 |
|------|---------|------|
| 별도 클래스 분리 | `findOrCreateUser()`를 다른 빈으로 이동해 외부 호출로 만듦 | 한 비즈니스 흐름이 두 클래스로 분산, 응집도 저하 |
| self 주입 | 자기 자신을 주입받아 프록시 경유 호출 | 순환 참조 위험, 가독성 저하, 안티패턴 |
| **TransactionTemplate** | **프록시 의존 자체를 제거**, `.execute()` 블록이 트랜잭션 범위 | 호출 토폴로지와 무관하게 항상 동작 |

**TransactionTemplate 채택 근거**:

1. **근본 원인의 제거** — 다른 두 후보는 self-invocation 문제를 *우회*하지만, TransactionTemplate은 *프록시 의존 자체를 없앤다*. 우회는 같은 함정을 다음 메서드에서 또 만나게 만들지만, 의존 제거는 함정의 가능성 자체를 닫는다
2. **호출 경로 무관성** — 같은 클래스 내부 호출이든 외부 호출이든 동일하게 동작. **추상화의 동작이 코드 토폴로지에 의존하지 않는다**
3. **프로젝트 표준과의 정합성** — `UserService`에서 이미 BCrypt 커넥션 풀 최적화를 위해 `TransactionTemplate`을 사용 중이었다. 이 결정으로 프로젝트 전반의 트랜잭션 관리 방식이 **하나의 일관된 패턴**으로 수렴한다 — 함께 따라오는 부수적 가치다

### 5. 수정 결과

```java
public OAuthLoginResponse googleLogin(String code) {

    // 1. Google API 호출 — 트랜잭션 밖 (외부 HTTP 통신, 수백ms)
    GoogleTokenResponse tokenResponse = googleOAuthClient.exchangeCodeForToken(code);
    Objects.requireNonNull(tokenResponse, "Google 토큰 응답이 null입니다");

    GoogleUserInfoResponse userInfo = googleOAuthClient.getUserInfo(tokenResponse.accessToken());
    Objects.requireNonNull(userInfo, "Google 유저 정보 응답이 null입니다");

    // 2. DB 조회/저장 — 트랜잭션 안 (TransactionTemplate으로 직접 관리)
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
                        userInfo.name(), userInfo.email(),
                        Provider.GOOGLE, userInfo.sub()
                );
                newUser.rewardLoginPoint(LocalDate.now());
                User savedUser = userRepository.save(newUser);
                return OAuthLoginResponse.from(savedUser, true);
            })
    );
}
```

별도 메서드 분리 없이 `googleLogin()` 하나로 통합했다.
`transactionTemplate.execute()` 블록 안이 곧 트랜잭션 범위이므로, self-invocation 문제가 원천적으로 발생하지 않는다.

### 변경 전/후 비교

| 항목 | 변경 전 | 변경 후 |
|---|---|---|
| 트랜잭션 방식 | `@Transactional` (미동작) | `TransactionTemplate` |
| 기존 유저 포인트 | DB 반영 안 됨 | 정상 반영 |
| 기존 유저 lastLoginDate | 갱신 안 됨 | 정상 갱신 |
| 신규 유저 생성 | 정상 | 정상 |
| Google API 호출 | 트랜잭션 밖 | 트랜잭션 밖 (동일) |

---

## 정리 — "추상화의 메커니즘을 이해하지 못한 사용은 침묵 속에 무너진다"

이 버그의 까다로움은 **신호의 부재**에 있다.

| 발견을 어렵게 만든 요인 | 의미 |
|----------------------|------|
| 컴파일 에러 없음 | 정적 분석이 보호하지 못함 |
| IDE 경고 없음 | 도구가 보호하지 못함 |
| 일부 시나리오 정상 동작 | 부분 성공이 전체 성공이라는 잘못된 확신을 만듦 |
| 기능 테스트로 발견 어려움 | 특정 시나리오 + DB 값 검증을 동시에 포함해야 발견 |

이 네 가지가 동시에 작용하면, **버그가 production에 도달할 확률이 매우 높다**. 그래서 이 버그의 진짜 교훈은 "self-invocation을 조심하라"가 아니라 **"추상화의 동작 메커니즘을 이해하지 못한 채 사용하면 침묵 속에 무너진다"** 는 일반 원리다.

### 프로젝트 전반의 표준화 결정

이 발견 이후 프로젝트의 트랜잭션 관리 방식을 **`TransactionTemplate` 기반으로 표준화**했다. 이 결정은 이 버그를 고치기 위한 임시 조치가 아니라, 다음 두 가지 가치를 동시에 확보하는 **일관된 설계 원칙**이다.

1. **프록시 의존 제거** — self-invocation 같은 함정을 원천적으로 방지. 추상화가 코드 토폴로지에 의존하지 않는다
2. **트랜잭션 범위 명시성** — `.execute()` 블록이 곧 트랜잭션 범위. BCrypt 커넥션 풀 최적화 같은 다른 설계 결정과도 자연스럽게 정합성을 가짐

이 표준화는 단일 버그의 해결을 넘어 **"추상화의 메커니즘을 명시적으로 드러내는 코드"** 라는 더 큰 설계 원칙을 프로젝트에 정착시키는 결정이었다. 어노테이션이 보장하지 못하는 것은 코드로 명시한다 — 이 원칙이 프록시 기반 추상화의 함정을 다음에 다시 만나지 않게 만든다.
