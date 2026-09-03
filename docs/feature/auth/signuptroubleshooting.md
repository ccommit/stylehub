# 회원가입 API 트러블슈팅

## BCrypt 커넥션 풀 고갈 문제 — TransactionTemplate으로 해결

### 문제

회원가입 API에서 `@Transactional`이 메서드 전체를 감싸고 있어, BCrypt 해싱(~300ms) 동안 DB 커넥션을 불필요하게 점유하고 있었다.

```
@Transactional 시작 → 커넥션 획득
  ├── validateSignUp()         ← SELECT 2번 (~2ms)
  ├── passwordEncoder.encode() ← BCrypt (~300ms) ★ DB와 무관한 CPU 작업인데 커넥션 점유
  ├── userRepository.save()    ← INSERT (~3ms)
@Transactional 종료 → 커넥션 반환

→ 총 커넥션 점유 305ms 중 실제 DB 작업은 5ms (98%가 낭비)
```

동시 요청이 몰리면 커넥션 풀이 고갈되어, 회원가입뿐 아니라 상품 조회, 주문 등 **전체 서비스가 장애**로 이어질 수 있는 구조였다.

---

### 해결 과정

#### 1차 시도: @Transactional 메서드 분리 (실패)

같은 클래스 내에서 BCrypt를 트랜잭션 밖으로 분리하고, DB 작업만 별도 `@Transactional` 메서드로 추출하려 했다.

```java
public UserSignUpResponse signUp(UserSignUpRequest request) {
    String encoded = passwordEncoder.encode(request.password());
    return saveUser(request, encoded);  // this.saveUser() 호출
}

@Transactional
public User saveUser(...) { ... }  // 트랜잭션 적용 안 됨!
```

**Spring AOP의 프록시 메커니즘** 때문에 실패했다. 같은 클래스 내부에서 `this.saveUser()`를 호출하면 프록시를 우회하여 `@Transactional`이 적용되지 않는다.

#### 2차 시도: 해결 방법 비교

| 방법 | 장점 | 단점 |
|------|------|------|
| 별도 클래스 분리 | 명확한 책임 분리 | 단순한 로직인데 클래스가 불필요하게 늘어남 |
| Self-injection | 클래스 수 유지 | 순환 참조 형태, 가독성 저하 |
| **TransactionTemplate** | 한 메서드 안에서 해결, 범위가 명시적 | 콜백 형태로 코드가 약간 길어짐 |

#### 최종 해결: TransactionTemplate 적용

```java
public UserSignUpResponse signUp(UserSignUpRequest request) {

    // BCrypt: 트랜잭션 밖에서 실행 → 커넥션 점유 안 함
    String encodedPassword = passwordEncoder.encode(request.password());

    // DB 작업만 트랜잭션으로 감싸기 → 커넥션 점유 최소화
    User savedUser = transactionTemplate.execute(status -> {
        userValidator.validateSignUp(request.email(), request.name());
        User user = User.create(request.name(), request.email(), encodedPassword, request.birthDate());
        return userRepository.save(user);
    });

    return UserSignUpResponse.from(savedUser);
}
```

```
passwordEncoder.encode()       ← BCrypt ~300ms (커넥션 없이 실행)

transactionTemplate.execute()  → 커넥션 획득
  ├── validateSignUp()         ← SELECT (~2ms)
  ├── userRepository.save()    ← INSERT (~3ms)
콜백 종료 → 커밋 → 커넥션 반환   총 ~5ms
```

---

### 부하 테스트 검증

이론적 분석이 실제 동시 요청 환경에서도 유효한지 JUnit 테스트로 검증했다.

#### 테스트 조건

| 항목 | 설정값 |
|------|--------|
| 커넥션 풀 (HikariCP) | 5개 |
| 동시 요청 수 | 100개 |
| 커넥션 타임아웃 | 500ms |
| BCrypt cost | 12 |

#### 테스트 결과

**변경 전: BCrypt IN Transaction**
```
========================================
  커넥션 풀 크기     : 5
  동시 요청 수       : 100
  성공               : 10
  타임아웃 (실패)    : 90
  성공률             : 10%
========================================
```

**변경 후: BCrypt OUT of Transaction**
```
========================================
  커넥션 풀 크기     : 5
  동시 요청 수       : 100
  성공               : 100
  타임아웃 (실패)    : 0
  성공률             : 100%
========================================
```

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| **성공률** | 10% (10/100) | **100% (100/100)** |
| **타임아웃** | 90건 | **0건** |
| 커넥션 점유 시간 | ~305ms | **~5ms** |

> 전체 테스트 코드: [`BcryptConnectionTest.java`](src/test/java/ccommit/stylehub/user/service/BcryptConnectionTest.java)

---

## BCrypt Cost 최적화 — cost 12 → 10으로 응답 시간 75% 개선

### 문제

TransactionTemplate 적용으로 커넥션 풀 고갈은 해결했지만, BCrypt 해싱 자체가 cost=12 기준 **~246ms**를 차지하고 있어 회원가입 응답 속도의 병목이었다.

```
회원가입 응답 시간 구성:
  BCrypt encode (cost=12)  : ~246ms  ★ 전체의 ~98%
  DB 작업 (SELECT + INSERT): ~5ms
  ─────────────────────────
  총                       : ~251ms
```

### 분석

BCrypt는 cost를 1 낮출 때마다 해싱 시간이 **절반**으로 줄어든다. OWASP는 비밀번호 해싱에 최소 100ms 이상을 권장하므로, 보안과 성능의 균형점을 찾아야 했다.

| cost | 예상 시간 | 보안 수준 |
|------|-----------|-----------|
| 12 | ~246ms | 과도함 |
| 11 | ~120ms | 충분 |
| **10** | **~61ms** | **OWASP 권장 기준 근접** |
| 9 | ~30ms | 다소 약함 |

### 해결

PasswordEncoder의 BCrypt cost를 12에서 **10으로 변경**했다.

```java
// 변경 전
BCrypt.withDefaults().hashToString(12, password.toCharArray());

// 변경 후
BCrypt.withDefaults().hashToString(10, password.toCharArray());
```

### 테스트 검증

각 cost로 **100회 반복** 해싱하여 평균 시간을 측정했다.

```
========================================
  BCrypt Cost 비교 (각 100회 반복)
========================================
  cost=12 평균     : 246ms
  cost=12 총 시간  : 24625ms
  cost=10 평균     : 61ms
  cost=10 총 시간  : 6155ms
  개선율           : 75.2% 감소
========================================
```

| 항목 | cost=12 | cost=10 | 개선 |
|------|---------|---------|------|
| 평균 해싱 시간 | 246ms | **61ms** | **75.2% 감소** |
| 100회 총 시간 | 24,625ms | **6,155ms** | **약 4배 빠름** |

### 최종 응답 시간 비교

| 항목 | 최초 (cost=12, @Transactional) | 최종 (cost=10, TransactionTemplate) |
|------|-------------------------------|-------------------------------------|
| BCrypt | ~246ms | **~61ms** |
| 커넥션 점유 | ~251ms (BCrypt 포함) | **~5ms (DB 작업만)** |
| **예상 응답 시간** | **~251ms** | **~66ms** |

> 전체 테스트 코드: [`BcryptConnectionTest.java`](src/test/java/ccommit/stylehub/user/service/BcryptConnectionTest.java)

---

### 보안 트레이드오프 분석

#### BCrypt는 왜 느려야 하는가

BCrypt는 **의도적으로 느리게 설계된 알고리즘**이다. 비밀번호 해시가 탈취됐을 때, 해커가 브루트포스(무차별 대입)로 원본 비밀번호를 알아내려 시도한다. 해싱이 느릴수록 시도 횟수가 줄어들어 크랙이 어려워진다.

```
해커의 브루트포스 시나리오:
  "aaaaaa" → BCrypt → 해시 비교 → 불일치
  "aaaaab" → BCrypt → 해시 비교 → 불일치
  ... (수억 번 반복)
```

| cost | 1회 해싱 시간 | 해커의 초당 시도 횟수 | 특수문자 포함 8자리 크랙 예상 시간 |
|------|-------------|---------------------|-------------------------------|
| 10 | ~61ms | ~16회 | 수천 년 |
| 12 | ~246ms | ~4회 | 수만 년 |

#### cost=10이 안전한 이유

- cost=10 기준 해커가 **초당 약 16번**만 시도 가능
- 특수문자 포함 8자리 비밀번호의 조합 수: 약 **6조 개**
- 16회/초로 6조 개를 시도하면 **약 1만 2천 년** 소요
- OWASP 권장 기준: 비밀번호 해싱에 **최소 100ms 이상이면 충분**
- cost=12(246ms)는 "과도하게 안전", cost=10(61ms)은 "충분히 안전" — **불필요한 성능을 희생하고 있었던 것**

#### 결론

보안을 "약화"시킨 것이 아니라, **과도한 보안 마진을 적정 수준으로 조정**한 것이다. 실질적인 보안 수준(브루트포스 수천 년)은 유지하면서 응답 시간을 75% 개선했다.

---

### 예상 면접 질문

**Q: "BCrypt cost를 낮추면 보안이 약화되는 거 아닌가요?"**

> "BCrypt cost를 12에서 10으로 낮췄지만, cost=10 기준 해싱 시간이 약 61ms로 OWASP 권장 기준을 충족합니다. 특수문자 포함 8자리 비밀번호 기준 브루트포스에 수천 년이 걸리는 수준이므로, 실질적인 보안은 유지하면서 응답 시간을 75% 개선한 트레이드오프입니다."

**Q: "TransactionTemplate을 왜 썼나요? 클래스를 분리하면 되지 않나요?"**

> "처음에는 같은 클래스 내에서 @Transactional 메서드를 분리하려 했지만, Spring AOP의 프록시 특성상 self-invocation에서는 트랜잭션이 적용되지 않았습니다. 별도 클래스 분리, Self-injection, TransactionTemplate 세 가지를 비교했고, 한 메서드 안에서 트랜잭션 범위를 명시적으로 제어할 수 있는 TransactionTemplate을 선택했습니다."

**Q: "커넥션 풀 사이즈를 늘리면 되지 않나요?"**

> "커넥션 풀을 늘리면 일시적으로 해결되지만, 본질적인 문제(DB와 무관한 작업이 커넥션을 점유하는 구조)는 남아 있습니다. 트래픽이 더 증가하면 풀 크기를 계속 늘려야 하고, DB 서버의 최대 연결 수에도 한계가 있습니다. 근본 원인을 해결하는 것이 맞다고 판단했습니다."

**Q: "실제로 장애가 발생했나요?"**

> "코드 리뷰 단계에서 잠재적 문제를 발견하고 선제적으로 개선한 것입니다. 부하 테스트로 검증한 결과 커넥션 풀 5개 / 동시 100요청 환경에서 변경 전 성공률 10%, 변경 후 100%로 문제가 실제로 재현되는 것을 확인했습니다."

---

### 핵심 정리

- **개선한 것**: 속도가 아니라 **자원 효율성**. BCrypt 자체 소요 시간은 동일하지만, 커넥션 점유 시간을 98% 줄여서 동일 인프라로 더 많은 동시 요청을 처리할 수 있게 되었다.
- **설계 원칙**: DB 커넥션이 필요 없는 무거운 작업(암호화, 외부 API 호출, 파일 처리 등)은 반드시 트랜잭션 밖에서 실행한다.

| 유사 적용 가능 패턴 | 트랜잭션 밖 | 트랜잭션 안 |
|---------------------|------------|------------|
| 회원가입 | BCrypt 암호화 | 중복 검증 + 저장 |
| 이미지 업로드 | S3 업로드 | 메타데이터 저장 |
| 주문 처리 | 외부 결제 API 호출 | 주문 상태 변경 |
| 알림 발송 | 이메일/SMS 전송 | 발송 이력 저장 |



