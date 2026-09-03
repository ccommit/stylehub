# 회원 API 동시성 및 버그 수정 기록

## 1. 회원가입 동시성 — Race Condition

### 문제

`validateSignUp()`에서 중복 체크 후 `save()` 사이에 다른 요청이 끼어들 수 있다.

```
시간  스레드A                         스레드B
──────────────────────────────────────────────────
t1   existsByEmail("a@b.com") → false
t2                                    existsByEmail("a@b.com") → false
t3   save(user) → 성공
t4                                    save(user) → DataIntegrityViolationException (500)
```

두 요청이 동시에 같은 이메일로 가입하면 DB unique 제약에 걸려 `DataIntegrityViolationException`이 발생하고, 사용자에게 500 에러가 노출된다.

### 해결

`DataIntegrityViolationException`을 catch하여 의미 있는 예외로 변환했다.

```java
try {
    savedUser = Objects.requireNonNull(
            transactionTemplate.execute(status -> {
                userValidator.validateSignUp(request.email(), request.name());
                User user = User.create(...);
                return userRepository.save(user);
            })
    );
} catch (DataIntegrityViolationException e) {
    throw new IllegalArgumentException("이미 사용 중인 이메일 또는 닉네임입니다");
}
```

`validateSignUp()`은 그대로 유지한다. 대부분의 정상 요청은 여기서 걸리고, DB 예외 catch는 동시 요청에 대한 안전망 역할이다.

### 수정 파일

- `UserService.java` — `signUp()` 메서드

---

## 2. 로그인 포인트 — 준영속 엔티티 변경 감지 불가

### 문제

```java
// 트랜잭션 1: 조회
User user = transactionTemplate.execute(status ->
        userRepository.findByEmail(...)
);
// ← 트랜잭션 종료 → user는 준영속(detached) 상태

// 트랜잭션 3: 변경
transactionTemplate.executeWithoutResult(status -> {
    user.rewardLoginPoint(LocalDate.now());  // dirty checking 동작 안 함
});
```

트랜잭션 1에서 조회한 `user`는 트랜잭션이 끝나면서 영속성 컨텍스트에서 분리된다(준영속 상태). 이후 트랜잭션 3에서 `rewardLoginPoint()`로 필드를 변경해도 JPA dirty checking이 동작하지 않아 DB에 변경 사항이 반영되지 않는다.

즉, 로그인할 때마다 포인트가 쌓여야 하는데 **실제로는 DB에 전혀 반영되지 않는 기능 버그**였다.

### 해결

트랜잭션 3에서 `findById`로 재조회하여 영속 상태의 엔티티를 얻은 뒤 변경한다.

```java
transactionTemplate.executeWithoutResult(status -> {
    User managedUser = userRepository.findById(user.getUserId()).orElseThrow();
    managedUser.rewardLoginPoint(LocalDate.now());
});
```

영속 상태의 `managedUser`는 트랜잭션 커밋 시점에 dirty checking이 동작하여 변경 사항이 자동으로 DB에 반영된다.

### 수정 파일

- `UserService.java` — `login()` 메서드

---

## 3. OAuth 로그인 동시성 — 동일 이메일 동시 최초 로그인

### 문제

같은 유저가 Google 로그인 버튼을 빠르게 두 번 누르면 회원가입과 동일한 Race Condition이 발생한다.

```
시간  스레드A                              스레드B
─────────────────────────────────────────────────────────
t1   findByEmail → empty
t2                                         findByEmail → empty
t3   save(newUser) → 성공
t4                                         save(newUser) → DataIntegrityViolationException (500)
```

### 해결

OAuth는 회원가입과 달리 동시 요청 시 에러가 아니라 **정상 로그인으로 처리**되어야 한다. `DataIntegrityViolationException` 발생 시 재조회하여 기존 유저로 로그인한다.

```java
try {
    return transactionTemplate.execute(status -> {
        Optional<User> existingUser = userRepository.findByEmail(userInfo.email());

        if (existingUser.isPresent()) {
            // 기존 유저 로그인 처리
        }

        // 신규 유저 저장
        User savedUser = userRepository.save(newUser);
        return OAuthLoginResponse.from(savedUser, true);
    });
} catch (DataIntegrityViolationException e) {
    // 동시 요청으로 이미 저장된 경우 → 재조회하여 기존 유저로 로그인 처리
    return transactionTemplate.execute(status -> {
        User user = userRepository.findByEmail(userInfo.email()).orElseThrow();
        user.rewardLoginPoint(LocalDate.now());
        return OAuthLoginResponse.from(user, false);
    });
}
```

회원가입(`signUp`)은 동시 요청 시 에러를 던지고, OAuth(`login`)은 재조회 후 정상 로그인으로 이어지는 차이가 있다. 이는 두 API의 비즈니스 요구사항이 다르기 때문이다.

### 수정 파일

- `OAuthService.java` — `login()` 메서드
