# TransactionTemplate 트랜잭션 분리 시 준영속 엔티티 변경 감지 실패

## 1. 배경

회원 로그인 API에서 BCrypt 비밀번호 검증(~100ms)이 트랜잭션 안에 있으면 DB 커넥션을 불필요하게 오래 점유한다. 대용량 트래픽 시 커넥션 풀이 고갈될 수 있어서 `TransactionTemplate`으로 트랜잭션을 분리했다.

```
트랜잭션 1: 유저 조회 → 커넥션 즉시 반환
트랜잭션 밖: BCrypt 검증 (~100ms, CPU 작업)
트랜잭션 2: 로그인 포인트 지급 + lastLoginDate 업데이트
```

커넥션 점유 시간을 최소화하는 좋은 설계였지만, 여기서 JPA 영속성 컨텍스트와 관련된 버그가 발생했다.

## 2. 문제 발견

로그인 시 포인트가 지급되어야 하는데 DB에 반영되지 않았다.

```java
// 트랜잭션 1: 유저 조회
User user = transactionTemplate.execute(status ->
        userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이메일입니다"))
);
// ← 트랜잭션 1 종료 → 영속성 컨텍스트 닫힘 → user는 준영속(detached) 상태

// BCrypt 검증 (트랜잭션 밖)
if (!passwordEncoder.matches(request.password(), user.getPassword())) {
    throw new IllegalArgumentException("비밀번호가 일치하지 않습니다");
}

// 트랜잭션 2: 포인트 지급
transactionTemplate.executeWithoutResult(status -> {
    user.rewardLoginPoint(LocalDate.now());  // pointBalance, lastLoginDate 변경
});
// ← 트랜잭션 2 커밋 → 하지만 DB에 아무 변경도 반영되지 않음
```

`rewardLoginPoint()`로 `pointBalance`와 `lastLoginDate`를 변경했지만, 실제 DB에는 아무런 UPDATE 쿼리도 나가지 않았다.

## 3. 원인 분석: JPA 영속성 컨텍스트와 dirty checking

### JPA 엔티티의 생명주기

```
비영속(new)  →  영속(managed)  →  준영속(detached)  →  삭제(removed)
              persist()         트랜잭션 종료/clear()    remove()
```

### dirty checking이란

JPA는 영속 상태의 엔티티 필드가 변경되면, 트랜잭션 커밋 시점에 자동으로 UPDATE 쿼리를 생성한다. 이것이 dirty checking이다.

**핵심: dirty checking은 영속(managed) 상태에서만 동작한다.**

### 왜 안 됐는가

```
트랜잭션 1 시작
  └─ 영속성 컨텍스트 A 생성
  └─ findByEmail() → user는 영속성 컨텍스트 A에서 관리됨 (managed)
트랜잭션 1 종료
  └─ 영속성 컨텍스트 A 닫힘 → user는 준영속(detached) 상태

트랜잭션 2 시작
  └─ 영속성 컨텍스트 B 생성 (새로운 컨텍스트)
  └─ user.rewardLoginPoint() → 자바 객체의 필드는 변경됨
  └─ 하지만 user는 영속성 컨텍스트 B에 등록되어 있지 않음
트랜잭션 2 커밋
  └─ 영속성 컨텍스트 B에 관리 중인 엔티티가 없음 → UPDATE 안 나감
```

`TransactionTemplate`은 호출할 때마다 새로운 영속성 컨텍스트를 생성한다. 트랜잭션 1에서 조회한 `user`는 트랜잭션 2의 영속성 컨텍스트에는 존재하지 않는다.

## 4. 해결

트랜잭션 2에서 `findById`로 재조회하여 영속 상태의 엔티티를 얻는다.

```java
// 트랜잭션 2: 포인트 지급
transactionTemplate.executeWithoutResult(status -> {
    User managedUser = userRepository.findById(user.getUserId()).orElseThrow();
    managedUser.rewardLoginPoint(LocalDate.now());
});
```

`managedUser`는 트랜잭션 2의 영속성 컨텍스트에서 관리되므로, 커밋 시 dirty checking이 정상 동작한다.

### 다른 해결 방법: merge

```java
transactionTemplate.executeWithoutResult(status -> {
    User managedUser = userRepository.save(user); // 내부적으로 merge() 호출
    managedUser.rewardLoginPoint(LocalDate.now());
});
```

`save()`에 이미 ID가 있는 엔티티를 넘기면 JPA는 `merge()`를 실행하여 준영속 엔티티를 영속성 컨텍스트에 다시 등록한다. 하지만 merge는 SELECT + UPDATE가 모두 발생하므로 `findById`로 재조회하는 것과 동작은 동일하다.

재조회 방식을 선택한 이유는 **의도가 명확하기 때문**이다. merge는 "이 엔티티를 다시 영속화한다"는 의미이고, findById는 "영속 상태의 엔티티를 가져온다"는 의미로 더 직관적이다.

## 5. 핵심 교훈

| 상황 | dirty checking |
|------|----------------|
| `@Transactional` 메서드 안에서 조회 + 변경 | 동작함 |
| `TransactionTemplate` 하나의 execute 안에서 조회 + 변경 | 동작함 |
| 트랜잭션 A에서 조회 → 트랜잭션 B에서 변경 | **동작 안 함** |

**트랜잭션을 분리하면 영속성 컨텍스트도 분리된다.** 트랜잭션 분리가 커넥션 최적화에는 좋지만, 엔티티의 영속 상태가 끊어진다는 점을 반드시 인지해야 한다.

### @Transactional을 썼다면 이 문제가 없었을까?

그렇다. 메서드 전체에 `@Transactional`을 걸었다면 하나의 영속성 컨텍스트에서 동작하므로 dirty checking이 정상 동작한다. 하지만 BCrypt(~100ms) 동안 DB 커넥션을 점유하게 되어 대용량 트래픽에서 커넥션 풀 고갈 위험이 있다.

트랜잭션 분리(커넥션 최적화)와 영속성 컨텍스트 유지(dirty checking) 사이의 트레이드오프를 이해하고 선택해야 한다.
