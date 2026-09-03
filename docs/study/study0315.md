# StyleHub 회원 API — 면접 대비 학습 가이드

> 우리가 구현한 코드를 설명하기 위해 알아야 할 개념들을 순서대로 정리했다.
> 앞의 개념이 뒤의 개념의 기반이 되므로, 순서대로 읽는 것을 권장한다.

---

## 1. 트랜잭션이란?

### 한 줄 요약
**"다 되거나, 다 안 되거나"를 보장하는 작업 단위.**                       

### 예시: 계좌 이체


A가 B에게 10,000원을 보낸다고 하자.

```
1단계: A 계좌에서 10,000원 차감
2단계: B 계좌에 10,000원 추가
```

만약 1단계는 성공했는데 2단계에서 서버가 죽으면?
A 돈은 빠졌는데 B는 못 받는다. 10,000원이 증발한다.

**트랜잭션**은 이걸 방지한다:
- 2단계까지 전부 성공하면 → **커밋** (확정)
- 중간에 하나라도 실패하면 → **롤백** (전부 취소, 1단계도 원복)

### 우리 코드에서의 트랜잭션

회원가입에서:

```
1단계: 이메일 중복 체크 (SELECT)
2단계: 닉네임 중복 체크 (SELECT)
3단계: 유저 저장 (INSERT)
```

이 3단계가 하나의 트랜잭션이다. 3단계에서 DB 에러가 나면 전부 롤백된다.

### 면접 Q&A

<details>
<summary><b>Q: 트랜잭션의 ACID 속성을 설명해주세요.</b></summary>

<details>
<summary>답변 보기</summary>

원자성(Atomicity)은 트랜잭션 안의 작업이 전부 성공하거나 전부 실패하는 것입니다. 일관성(Consistency)은 트랜잭션 전후로 데이터 무결성이 유지되는 것이고, 격리성(Isolation)은 동시에 실행되는 트랜잭션이 서로 간섭하지 않는 것입니다. 지속성(Durability)은 커밋된 데이터가 영구 저장되는 것입니다. StyleHub 회원가입에서는 이메일 중복 체크와 유저 저장을 하나의 트랜잭션으로 묶어 원자성을 보장했습니다. 중복 체크 통과 후 INSERT에서 실패하면 전부 롤백됩니다.
</details>
</details>

<details>
<summary><b>Q: 트랜잭션에서 롤백은 언제 발생하나요?</b></summary>

<details>
<summary>답변 보기</summary>

Spring에서 `@Transactional`은 기본적으로 RuntimeException(Unchecked Exception) 발생 시 롤백합니다. Checked Exception은 롤백하지 않습니다. TransactionTemplate도 동일합니다. 우리 코드에서는 IllegalArgumentException(RuntimeException)을 던지므로, 이메일 중복이 발견되면 자동 롤백됩니다.
</details>

<details>
<summary><b>꼬리질문: 그럼 Checked Exception이 발생하면 커밋되나요? 그게 맞는 동작인가요?</b></summary>

<details>
<summary>답변 보기</summary>

네, 기본적으로 커밋됩니다. Spring 설계 철학은 "Checked Exception은 비즈니스적으로 예상된 예외이므로 복구 가능하다"는 것입니다. 예를 들어 파일 업로드 시 IOException이 발생해도, 이미 DB에 저장한 메타데이터는 유지하고 싶을 수 있습니다. 하지만 모든 예외에서 롤백하고 싶으면 `@Transactional(rollbackFor = Exception.class)`로 설정할 수 있습니다.
</details>

<details>
<summary><b>꼬리질문: 트랜잭션 격리 수준(Isolation Level)에 대해 설명해주세요.</b></summary>

<details>
<summary>답변 보기</summary>

동시에 실행되는 트랜잭션이 서로 얼마나 간섭할 수 있는지를 결정하는 수준입니다. READ_UNCOMMITTED는 커밋되지 않은 데이터도 읽을 수 있고(Dirty Read), READ_COMMITTED는 커밋된 데이터만 읽습니다. REPEATABLE_READ는 같은 트랜잭션 안에서 같은 데이터를 반복 읽어도 동일한 결과를 보장합니다. SERIALIZABLE은 완전 격리지만 성능이 가장 낮습니다. MySQL InnoDB의 기본값은 REPEATABLE_READ입니다.
</details>

<details>
<summary><b>꼬리질문: REPEATABLE_READ에서도 발생할 수 있는 문제가 있나요?</b></summary>

<details>
<summary>답변 보기</summary>

팬텀 리드(Phantom Read)가 발생할 수 있습니다. 같은 조건으로 조회했는데 다른 트랜잭션이 INSERT한 새로운 행이 보이는 현상입니다. 하지만 MySQL InnoDB는 Gap Lock으로 팬텀 리드도 방지합니다. 그래서 실질적으로 MySQL에서는 REPEATABLE_READ가 SERIALIZABLE에 가까운 수준의 격리를 제공합니다.
</details>

<details>
<summary><b>꼬리질문: StyleHub 회원가입에서 동시에 같은 이메일로 가입하면 어떤 격리 수준이 필요한가요?</b></summary>

<details>
<summary>답변 보기</summary>

격리 수준만으로는 해결이 안 됩니다. 두 트랜잭션이 동시에 existsByEmail()로 조회하면 둘 다 "없음"을 읽고 INSERT를 시도합니다. 이건 SERIALIZABLE에서도 발생할 수 있습니다. 그래서 DB의 unique 제약조건으로 방어합니다. 하나의 INSERT만 성공하고 나머지는 DataIntegrityViolationException이 발생합니다. 이것이 애플리케이션 레벨 검증(existsByEmail) + DB 레벨 방어(unique constraint) 이중 방어 전략입니다.
</details>
</details>
</details>
</details>
</details>
</details>

---

## 2. DB 커넥션과 커넥션 풀

### 한 줄 요약
**커넥션은 서버와 DB 사이의 전화선이고, 커넥션 풀은 전화선 묶음이다.**

### 예시: 은행 창구

```
은행 창구 = DB 커넥션
은행 전체 창구 수 = 커넥션 풀 크기
고객 = API 요청
```

은행에 창구가 10개 있다.
고객 10명이 동시에 오면 → 1명당 1개 창구 배정, 전부 처리 가능.
고객 50명이 동시에 오면 → 10명은 창구 사용, 40명은 대기.
대기 시간이 너무 길면 → **"오늘 업무 마감입니다" (타임아웃, 에러)**

### 커넥션이 비싼 이유

DB 커넥션을 새로 만드는 건 느리다 (TCP 연결, 인증 등 ~수십ms).
그래서 미리 10개 만들어놓고 돌려쓴다. 이게 **커넥션 풀**이다.
Spring Boot는 기본으로 **HikariCP**라는 커넥션 풀을 사용한다. 기본 크기는 10개.

### 커넥션 풀 고갈이 위험한 이유

커넥션 풀은 **모든 API가 공유**한다.

```
로그인 API      → 커넥션 필요
상품 조회 API    → 커넥션 필요
주문 API        → 커넥션 필요
결제 API        → 커넥션 필요
```

로그인이 커넥션을 오래 잡으면:

```
로그인 10건이 커넥션 10개를 100ms씩 점유
→ 상품 조회 요청 → 커넥션 없음 → 대기 → 타임아웃
→ 주문 요청 → 커넥션 없음 → 대기 → 타임아웃
→ 전체 서비스 장애
```

**하나의 API가 전체 서비스를 죽이는 것.** 이걸 **장애 전파**라고 한다.

### 면접 Q&A

<details>
<summary><b>Q: HikariCP의 기본 커넥션 풀 크기는 얼마이고, 어떻게 결정하나요?</b></summary>

<details>
<summary>답변 보기</summary>

Spring Boot 기본값은 10개입니다. HikariCP 공식 문서에서 권장하는 공식은 `커넥션 수 = CPU 코어 수 × 2 + 디스크 수`입니다. 예를 들어 4코어 서버면 약 10개가 적절합니다. 무조건 크게 잡으면 DB 서버에 부담이 가고, 너무 작으면 커넥션 대기가 발생합니다. StyleHub에서는 기본값 10개를 사용하되, BCrypt 커넥션 점유를 최소화하여 10개로도 충분하도록 설계했습니다.
</details>
</details>


<details>
<summary><b>Q: 커넥션 풀 고갈이 발생하면 어떤 현상이 나타나나요?</b></summary>

<details>
<summary>답변 보기</summary>

새로운 요청이 커넥션을 획득하지 못하고 대기합니다. HikariCP의 기본 connectionTimeout은 30초이고, 30초 안에 커넥션을 못 얻으면 SQLTransientConnectionException이 발생합니다. 문제는 로그인뿐 아니라 상품 조회, 주문 등 모든 API가 같은 풀을 공유하기 때문에 하나의 API 병목이 전체 서비스 장애로 이어집니다. 이것을 장애 전파라고 합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 커넥션 풀 크기를 늘리면 해결되지 않나요?</b></summary>

<details>
<summary>답변 보기</summary>

단기적으로는 완화되지만 근본 해결이 아닙니다. 풀을 20개로 늘리면 동시 처리량이 2배가 되지만, 트래픽이 2배로 늘면 다시 고갈됩니다. 또한 DB 서버 입장에서 커넥션이 많으면 메모리와 스레드 부담이 커지고, DB 성능이 오히려 떨어질 수 있습니다. 풀 크기를 늘리는 것보다 커넥션 점유 시간을 줄이는 것이 근본적인 해결입니다. StyleHub에서는 TransactionTemplate으로 점유 시간을 103ms에서 6ms로 줄여서 풀 10개로도 충분하게 만들었습니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 커넥션 풀과 스레드 풀의 차이는 무엇인가요?</b></summary>

<details>
<summary>답변 보기</summary>

커넥션 풀은 DB와의 연결을 재사용하는 것이고, 스레드 풀은 요청을 처리하는 작업자 스레드를 재사용하는 것입니다. Tomcat의 기본 스레드 풀은 200개이고, HikariCP의 기본 커넥션 풀은 10개입니다. 200개의 스레드가 동시에 DB 작업을 하면 10개의 커넥션을 경쟁하게 됩니다. 커넥션 점유 시간이 길수록 이 경쟁이 심해지고, 스레드가 커넥션 대기로 블로킹됩니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 스레드가 커넥션 대기 중이면 다른 요청도 처리 못하나요?</b></summary>

<details>
<summary>답변 보기</summary>

네, 블로킹됩니다. Tomcat 스레드 200개 중 190개가 커넥션 대기 중이면, 나머지 10개만 요청을 처리합니다. 최악의 경우 200개 전부 대기에 빠지면 새로운 HTTP 요청 자체를 받을 수 없습니다. 이게 커넥션 풀 고갈이 단순히 DB 작업만 느려지는 게 아니라 전체 서버가 먹통이 되는 이유입니다.
</details>
</details>


---

## 3. @Transactional의 동작 원리

### 한 줄 요약
**메서드에 `@Transactional`을 붙이면, 메서드 시작 시 커넥션을 획득하고 끝날 때 반환한다.**

### 내부 동작

```java
@Transactional
public void doSomething() {
    // 이 메서드가 호출되면:
    // 1. 커넥션 풀에서 커넥션 획득
    // 2. setAutoCommit(false) 호출
    // 3. 메서드 실행
    // 4. 성공하면 커밋, 실패하면 롤백
    // 5. 커넥션 반환
}
```

**핵심:** 메서드 **진입부터 종료까지** 커넥션을 잡고 있다.
메서드 안에서 DB를 안 쓰는 코드가 있어도, 커넥션은 반환되지 않는다.

### 예시: 편의점 알바

```
@Transactional = "출근부터 퇴근까지 카운터를 점유한다"

출근 (메서드 시작) → 카운터 점유 (커넥션 획득)
  ├─ 손님 계산 (DB 작업) ← 카운터 사용 중
  ├─ 창고 정리 (BCrypt) ← 카운터 안 쓰지만 점유 중!
  └─ 퇴근 (메서드 종료) → 카운터 반환 (커넥션 반환)
```

창고 정리하는 동안 카운터가 비어있지만 다른 알바가 못 쓴다.

### 면접 Q&A

<details>
<summary><b>Q: @Transactional은 내부적으로 어떻게 동작하나요?</b></summary>

<details>
<summary>답변 보기</summary>

Spring AOP 프록시를 통해 동작합니다. 메서드 호출 시 TransactionInterceptor가 가로채서 JpaTransactionManager.doBegin()을 실행합니다. 이때 Hibernate가 setAutoCommit(false)를 호출하기 위해 커넥션을 즉시 획득합니다. 메서드가 정상 종료되면 커밋, 예외가 발생하면 롤백 후 커넥션을 반환합니다. 핵심은 메서드 진입부터 종료까지 커넥션이 점유된다는 점입니다.
</details>
</details>


<details>
<summary><b>Q: @Transactional(readOnly = true)의 장점은 무엇인가요?</b></summary>

<details>
<summary>답변 보기</summary>

두 가지입니다. 첫째, Hibernate가 더티 체킹을 위한 스냅샷을 생성하지 않아 메모리와 CPU를 절약합니다. 둘째, MySQL InnoDB에서 읽기 전용 트랜잭션에 대한 내부 최적화를 적용합니다. 하지만 커넥션 점유 시간은 줄이지 못합니다. readOnly는 트랜잭션의 "성격"을 바꿀 뿐 "범위"는 바꾸지 않습니다. 그래서 StyleHub 로그인에서는 readOnly 대신 TransactionTemplate으로 커넥션 범위 자체를 줄였습니다.
</details>
</details>


<details>
<summary><b>꼬리질문: @Transactional의 전파 속성(Propagation)을 설명해주세요.</b></summary>

<details>
<summary>답변 보기</summary>

이미 트랜잭션이 존재할 때 새로운 트랜잭션을 어떻게 처리할지 결정하는 속성입니다. 기본값은 REQUIRED로, 기존 트랜잭션이 있으면 참여하고 없으면 새로 생성합니다. REQUIRES_NEW는 항상 새 트랜잭션을 만들고 기존 것을 일시 중단합니다. NESTED는 기존 트랜잭션 안에서 세이브포인트를 만들어 부분 롤백이 가능합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: REQUIRED와 REQUIRES_NEW의 차이가 실무에서 중요한 이유는?</b></summary>

<details>
<summary>답변 보기</summary>

예를 들어 주문 처리 중 알림 발송이 실패해도 주문은 성공해야 한다면, 알림을 REQUIRES_NEW로 별도 트랜잭션으로 분리합니다. REQUIRED이면 알림 실패가 주문까지 롤백시킵니다. 반대로 회원가입에서 중복 체크와 저장은 반드시 같은 트랜잭션이어야 하므로 REQUIRED가 적절합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: @Transactional을 클래스 레벨에 붙이는 것과 메서드 레벨에 붙이는 것의 차이는?</b></summary>

<details>
<summary>답변 보기</summary>

클래스 레벨에 붙이면 모든 public 메서드에 적용됩니다. 메서드 레벨은 해당 메서드에만 적용되고, 클래스 레벨보다 우선합니다. 일반적으로 클래스 레벨에 `@Transactional(readOnly = true)`를 붙이고, 데이터를 변경하는 메서드에만 `@Transactional`을 오버라이드하는 패턴을 많이 사용합니다. StyleHub에서는 TransactionTemplate을 사용하기 때문에 클래스 레벨 @Transactional을 사용하지 않습니다.
</details>
</details>


---

## 4. BCrypt란?

### 한 줄 요약
**비밀번호를 되돌릴 수 없게 변환하는 알고리즘. 의도적으로 느리다.**

### 왜 비밀번호를 그냥 저장하면 안 되는가

```
[DB가 해킹당했을 때]

평문 저장:     password123! → 해커가 바로 사용 가능
BCrypt 저장:  $2a$10$lrEzr4O... → 해커가 원본을 알 수 없음
```

### 왜 의도적으로 느린가

해커가 무차별 대입 공격(모든 비밀번호를 하나씩 시도)을 할 때:

```
SHA-256 (빠른 해시): 초당 수십억 번 시도 가능 → 금방 뚫림
BCrypt (느린 해시):  초당 수십 번만 시도 가능 → 사실상 불가능
```

BCrypt의 **cost** 값으로 속도를 조절한다:

| cost | 해싱 시간 | 초당 시도 가능 횟수 |
|---|---|---|
| 10 | ~100ms | ~10회 |
| 12 | ~400ms | ~2.5회 |
| 14 | ~1,600ms | ~0.6회 |

cost를 1 올릴 때마다 시간이 **2배**로 늘어난다.
우리는 cost=10을 사용한다. 보안과 성능의 균형점이다.

### 회원가입과 로그인에서의 BCrypt

```
[회원가입] BCrypt.hash("password123!") → "$2a$10$lrEzr4O..." 저장 (~100ms)
[로그인]   BCrypt.verify("password123!", "$2a$10$lrEzr4O...") → true/false (~100ms)
```

해싱(hash)과 검증(verify) 모두 ~100ms가 걸린다. 이게 커넥션 풀 문제의 원인이다.

### 면접 Q&A

<details>
<summary><b>Q: BCrypt의 cost를 10으로 설정한 이유는?</b></summary>

<details>
<summary>답변 보기</summary>

OWASP에서 BCrypt 해싱에 최소 100ms 이상을 권장합니다. cost=10은 약 80~100ms로 이 기준을 충족하면서, cost=12(~400ms)보다 약 4배 빠릅니다. 대용량 트래픽 환경에서는 해싱 시간이 곧 CPU 점유 시간이므로, 보안 기준을 만족하는 최소 cost를 선택했습니다. 실제로 대부분의 서비스가 cost=10을 사용합니다.
</details>
</details>


<details>
<summary><b>Q: BCrypt 대신 SHA-256을 쓰면 안 되나요?</b></summary>

<details>
<summary>답변 보기</summary>

SHA-256은 범용 해시 함수로 매우 빠릅니다. 초당 수십억 번 연산이 가능해서 무차별 대입 공격에 취약합니다. BCrypt는 비밀번호 해싱 전용으로 설계되어 의도적으로 느리고, cost 값으로 연산 속도를 조절할 수 있어 하드웨어가 발전해도 cost만 올리면 대응됩니다. 비밀번호 저장에는 반드시 BCrypt, scrypt, Argon2 같은 느린 해시를 사용해야 합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: BCrypt, scrypt, Argon2 중 어떤 것이 가장 좋은가요?</b></summary>

<details>
<summary>답변 보기</summary>

현재 가장 권장되는 것은 Argon2입니다. 2015년 Password Hashing Competition 우승 알고리즘으로, CPU뿐 아니라 메모리 사용량도 조절할 수 있어 GPU 기반 공격에도 강합니다. BCrypt는 CPU만 조절 가능하고, scrypt는 메모리도 조절 가능하지만 파라미터 설정이 복잡합니다. 다만 BCrypt는 1999년부터 25년 이상 검증되었고, 대부분의 프레임워크에서 기본 지원하므로 여전히 안전한 선택입니다.
</details>
</details>


<details>
<summary><b>꼬리질문: BCrypt의 salt는 어떻게 동작하나요?</b></summary>

<details>
<summary>답변 보기</summary>

BCrypt는 해싱할 때마다 랜덤 salt를 자동 생성합니다. 같은 비밀번호를 두 번 해싱해도 결과가 다릅니다. salt는 해시 결과 문자열 안에 포함됩니다. `$2a$10$lrEzr4O9vyev58/NaZRF0e...`에서 `$2a$`는 알고리즘, `10$`는 cost, 그 뒤 22자가 salt, 나머지가 해시값입니다. 검증 시에는 저장된 해시에서 salt를 추출하여 동일한 조건으로 해싱 후 비교합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 같은 비밀번호인데 해시가 다르면 어떻게 로그인 시 비교하나요?</b></summary>

<details>
<summary>답변 보기</summary>

BCrypt.verify()가 저장된 해시 문자열에서 salt와 cost를 추출하고, 입력된 비밀번호를 동일한 salt와 cost로 다시 해싱합니다. 그 결과와 저장된 해시를 비교합니다. salt가 해시 안에 포함되어 있기 때문에, 별도로 salt를 DB에 저장할 필요가 없습니다.
</details>
</details>


---

## 5. 우리가 해결한 문제 — BCrypt + @Transactional = 커넥션 낭비

### 문제 상황

```java
@Transactional
public UserLoginResponse login(UserLoginRequest request) {
    // ← 커넥션 획득 (메서드 시작)
    User user = userRepository.findByEmail(request.email());        // ~3ms  (커넥션 사용)
    passwordEncoder.matches(request.password(), user.getPassword()); // ~100ms (커넥션 안 쓰지만 점유!)
    return UserLoginResponse.from(user);
    // ← 커넥션 반환 (메서드 종료)
}
```

```
커넥션 점유 시간: 3ms(DB) + 100ms(BCrypt) = 103ms
실제 DB 사용 시간: 3ms
낭비 시간: 100ms (97%가 낭비)
```

### 예시: 택시

```
택시(커넥션)를 타고 마트(DB)에 갔다.
마트에서 장보기 3분 (DB 작업).
집에서 요리 100분 (BCrypt).
요리하는 동안 택시가 집 앞에서 대기 중. 미터기 계속 올라감.

→ 마트 갔다 온 뒤 택시를 보내고, 요리 끝나면 다시 택시를 부르면 된다.
```

### 면접 Q&A

<details>
<summary><b>Q: BCrypt가 커넥션을 사용하지 않는데 왜 커넥션 점유가 문제인가요?</b></summary>

<details>
<summary>답변 보기</summary>

BCrypt 자체는 커넥션을 안 씁니다. 하지만 @Transactional이 메서드 진입 시점에 커넥션을 먼저 획득하기 때문에, BCrypt가 트랜잭션 생명주기 안에 포함되면 결과적으로 커넥션이 점유됩니다. 문제는 "BCrypt가 커넥션을 쓰느냐"가 아니라 "@Transactional의 커넥션 생명주기 안에 BCrypt가 포함되느냐"입니다. 호텔 방을 체크인한 상태에서 밖에서 산책해도 방은 다른 손님이 못 쓰는 것과 같습니다.
</details>
</details>


<details>
<summary><b>Q: 이 문제를 어떻게 발견했나요?</b></summary>

<details>
<summary>답변 보기</summary>

커넥션 점유 시간을 계산해봤습니다. @Transactional 메서드 안에서 BCrypt가 ~100ms 걸리는데, 실제 DB 작업은 ~3ms뿐이었습니다. 커넥션 점유의 97%가 DB와 무관한 CPU 작업이었고, HikariCP 풀 10개 기준으로 초당 97건밖에 처리 못한다는 계산이 나왔습니다. 이후 부하 테스트로 실제 커넥션 풀 고갈을 확인했습니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 부하 테스트는 어떻게 했나요?</b></summary>

<details>
<summary>답변 보기</summary>

Spring Context 없이 순수 JDBC + HikariCP로 테스트를 작성했습니다. 커넥션 풀 5개, 동시 100건, 타임아웃 500ms로 설정하고, 변경 전(BCrypt IN 트랜잭션)은 100건 중 90건 타임아웃, 변경 후(BCrypt OUT 트랜잭션)는 100건 전부 성공했습니다. Spring Context를 띄우지 않고 커넥션 풀 동작만 격리하여 검증한 이유는, 다른 변수를 제거하고 커넥션 점유 문제만 증명하기 위해서입니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 이론적 계산과 실측값이 다를 수 있지 않나요?</b></summary>

<details>
<summary>답변 보기</summary>

맞습니다. 이론적 계산은 DB 쿼리 시간, 네트워크 지연, GC, 컨텍스트 스위칭 등을 무시한 값입니다. 실제로는 JMeter나 k6 같은 성능 테스트 도구로 실제 API 엔드포인트에 부하를 주어 측정해야 합니다. 다만 이론적 계산으로도 "103ms vs 6ms"라는 차이의 방향성과 규모를 확인하기에는 충분합니다.
</details>
</details>


---

## 6. TransactionTemplate — 해결책

### 한 줄 요약
**트랜잭션(= 커넥션) 범위를 코드에서 직접 지정한다.**

### @Transactional과의 차이

```java
// @Transactional: 메서드 전체가 트랜잭션
@Transactional
public void method() {
    dbWork();      // 커넥션 사용
    cpuWork();     // 커넥션 안 쓰지만 점유 중
    dbWork2();     // 커넥션 사용
}
// ← 메서드 끝나야 커넥션 반환
```

```java
// TransactionTemplate: 블록 단위로 트랜잭션
public void method() {
    transactionTemplate.execute(status -> {
        dbWork();  // 커넥션 사용
        return result;
    });
    // ← 여기서 커넥션 반환!

    cpuWork();  // 커넥션 없음

    transactionTemplate.executeWithoutResult(status -> {
        dbWork2();  // 다시 커넥션 획득
    });
    // ← 여기서 커넥션 반환!
}
```

### 예시: 택시 비유 다시

```
[@Transactional]
택시 탑승 → 마트 → 집에서 요리(100분, 택시 대기) → 우체국 → 택시 하차
총 택시 대기: 103분

[TransactionTemplate]
택시 탑승 → 마트 → 택시 하차 (3분)
집에서 요리 (100분, 택시 없음)
택시 탑승 → 우체국 → 택시 하차 (3분)
총 택시 대기: 6분
```

같은 일을 하는데 택시비(커넥션 점유)가 **17배 차이**난다.

### 면접 Q&A

<details>
<summary><b>Q: @Transactional 대신 TransactionTemplate을 선택한 이유는?</b></summary>

<details>
<summary>답변 보기</summary>

세 가지 이유입니다. 첫째, @Transactional은 메서드 단위로 트랜잭션이 열리기 때문에 BCrypt 같은 CPU 작업을 트랜잭션 밖으로 뺄 수 없습니다. TransactionTemplate은 execute() 블록 단위로 제어 가능합니다. 둘째, @Transactional은 같은 클래스 내부 호출 시 self-invocation 문제로 동작하지 않을 수 있지만, TransactionTemplate은 프록시에 의존하지 않아 항상 동작합니다. 셋째, 트랜잭션 범위가 코드에 시각적으로 드러나서 코드 리뷰 시 커넥션 점유 구간을 쉽게 파악할 수 있습니다.
</details>
</details>


<details>
<summary><b>Q: TransactionTemplate의 단점은 없나요?</b></summary>

<details>
<summary>답변 보기</summary>

코드가 콜백 형태라 @Transactional보다 약간 길어집니다. 또한 선언적 방식(@Transactional)이 아닌 프로그래밍 방식이라, 팀 내 컨벤션이 없으면 혼용되어 일관성이 깨질 수 있습니다. 하지만 BCrypt처럼 CPU 작업을 분리해야 하는 경우에는 TransactionTemplate이 유일한 선택지이고, 3~4줄 추가로 커넥션 처리량 17배 개선을 얻으니 충분히 합리적인 트레이드오프입니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 그러면 모든 서비스에서 TransactionTemplate을 쓰는 게 좋은가요?</b></summary>

<details>
<summary>답변 보기</summary>

아닙니다. TransactionTemplate은 트랜잭션 범위를 세밀하게 제어해야 할 때 사용합니다. 단순 CRUD처럼 메서드 전체가 DB 작업인 경우에는 @Transactional이 더 간결하고 적합합니다. StyleHub에서도 BCrypt나 외부 API 호출처럼 트랜잭션 밖으로 빼야 할 작업이 있는 메서드에만 TransactionTemplate을 사용하고, 향후 단순 조회 API 같은 곳에서는 @Transactional(readOnly = true)을 사용할 수 있습니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 선언적 트랜잭션과 프로그래밍 방식 트랜잭션의 차이를 정리해주세요.</b></summary>

<details>
<summary>답변 보기</summary>

선언적(@Transactional)은 어노테이션으로 트랜잭션을 관리하고, AOP 프록시가 처리합니다. 코드가 간결하지만 메서드 단위로만 제어 가능하고, self-invocation 문제가 있습니다. 프로그래밍 방식(TransactionTemplate)은 코드에서 직접 트랜잭션을 열고 닫습니다. 블록 단위로 세밀한 제어가 가능하고, 프록시에 의존하지 않아 항상 동작합니다. 다만 코드가 약간 길어집니다. 둘 다 내부적으로는 PlatformTransactionManager를 사용합니다.
</details>
</details>


---

## 7. 우리 로그인 코드의 3단계 구조

```java
public UserLoginResponse login(UserLoginRequest request) {

    // 1단계: 유저 조회 (트랜잭션 — 커넥션 점유 ~3ms)
    User user = Objects.requireNonNull(
            transactionTemplate.execute(status ->
                    userRepository.findByEmail(request.email())
                            .orElseThrow(...)
            )
    );
    // ← 커넥션 반환

    // 2단계: BCrypt 검증 (트랜잭션 밖 — 커넥션 없음 ~100ms)
    if (!passwordEncoder.matches(request.password(), user.getPassword())) {
        throw new IllegalArgumentException("비밀번호가 일치하지 않습니다");
    }

    // 3단계: 포인트 지급 (트랜잭션 — 커넥션 점유 ~3ms)
    transactionTemplate.executeWithoutResult(status -> {
        user.rewardLoginPoint(LocalDate.now());
    });
    // ← 커넥션 반환

    return UserLoginResponse.from(user);
}
```

```
시간 →

0ms    3ms                         103ms   106ms
 |      |                            |       |
 |◄TX1►|                            |◄TX2 ►|
 |SELECT|    BCrypt (~100ms)         |UPDATE |
 |커넥션 |    커넥션 없음              |커넥션  |

총 커넥션 점유: 6ms
총 응답 시간: 106ms
```

### 면접 Q&A

<details>
<summary><b>Q: 트랜잭션을 2개로 나누면 데이터 일관성에 문제가 없나요?</b></summary>

<details>
<summary>답변 보기</summary>

트랜잭션 1(유저 조회)과 트랜잭션 2(포인트 지급) 사이에 유저가 삭제되거나, 같은 유저가 동시에 로그인하여 포인트가 중복 지급될 가능성이 있습니다. 하지만 로그인 중 유저 삭제는 비정상 시나리오이고, 포인트 중복은 최악의 경우 10P 수준으로 비즈니스적으로 무시 가능합니다. 커넥션 점유 17배 감소라는 이점 대비 감수할 수 있는 트레이드오프로 판단했습니다.
</details>
</details>


<details>
<summary><b>Q: 이 최적화로 실제 얼마나 개선되나요?</b></summary>

<details>
<summary>답변 보기</summary>

커넥션 풀 관점에서 점유 시간이 103ms에서 6ms로 약 17배 감소했습니다. 풀 10개 기준 이론적 처리량은 97 req/s에서 1,660 req/s로 증가합니다. 하지만 실제 한계는 BCrypt의 CPU 병목입니다. 8코어 기준 초당 약 80건이 한계입니다. 이 최적화의 진짜 가치는 처리량 자체보다 장애 격리에 있습니다. 로그인이 폭주해도 커넥션 풀은 여유가 있으므로 상품 조회, 주문 등 다른 API가 정상 동작합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: CPU 병목이 진짜 한계라면 이 최적화에 의미가 있나요?</b></summary>

<details>
<summary>답변 보기</summary>

의미가 큽니다. 최적화 전에는 커넥션 풀 + CPU 두 가지 모두 병목이었습니다. 최적화 후에는 CPU만 병목이 되고 커넥션 풀은 여유가 생겼습니다. 핵심은 로그인 API의 CPU 병목이 다른 API로 전파되지 않는다는 것입니다. 로그인이 CPU 한계에 걸려 느려져도, 상품 조회나 주문 API는 커넥션을 정상적으로 사용할 수 있습니다.
</details>
</details>


<details>
<summary><b>꼬리질문: CPU 병목은 어떻게 해결할 수 있나요?</b></summary>

<details>
<summary>답변 보기</summary>

수평 확장(서버 추가)이 가장 직접적입니다. 8코어 서버 1대가 80 req/s라면 2대면 160 req/s입니다. JWT가 stateless이므로 서버 간 상태 공유 없이 로드 밸런서만 두면 됩니다. BCrypt cost를 낮추는 것도 방법이지만 보안과의 트레이드오프입니다. 비동기 처리로 BCrypt를 별도 스레드 풀에서 실행하는 방법도 있지만, 복잡도가 크게 증가합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 포인트 중복 지급 문제를 완전히 방어하려면 어떻게 해야 하나요?</b></summary>

<details>
<summary>답변 보기</summary>

비관적 락(SELECT ... FOR UPDATE)으로 유저 행을 잠그면 됩니다. 하지만 락을 걸면 동일 유저의 동시 로그인이 직렬화되어 성능이 떨어집니다. 10P 중복 방어를 위해 락을 거는 건 비용 대비 효과가 맞지 않습니다. 만약 포인트가 금전적 가치가 커서 정확해야 한다면, 비관적 락이나 Redis의 SETNX로 일일 로그인 여부를 원자적으로 체크하는 방법이 있습니다.
</details>
</details>


---

## 8. AOP 프록시란?

### 한 줄 요약
**Spring이 내 클래스를 감싸는 "대리인"을 만들어서, 메서드 호출 전후에 추가 작업을 한다.**

### 예시: 비서

사장(내 코드)에게 비서(프록시)가 있다.
외부 손님(컨트롤러)이 사장에게 연락하면 비서가 먼저 받는다.

```
[외부에서 호출]
손님 → 비서(프록시) → "회의실 예약할게요(트랜잭션 시작)" → 사장(실제 메서드) → "회의실 반납(트랜잭션 종료)"
```

하지만 사장이 **직접** 자기 다른 업무를 하면 비서를 안 거친다:

```
[같은 클래스 내부 호출]
사장 → 사장의 다른 업무 (비서 안 거침) → 회의실 예약 안 됨!
```

이게 **self-invocation 문제**다.

### 코드로 보면

```java
public class OAuthService {

    public void methodA() {
        methodB();  // this.methodB() → 프록시 안 거침 → @Transactional 무시!
    }

    @Transactional
    public void methodB() {
        // 트랜잭션이 적용될 거라 기대하지만, 안 됨
    }
}
```

```
[외부에서 methodB 호출]
Controller → OAuthService$$Proxy.methodB() → 프록시가 트랜잭션 시작 ✅

[methodA에서 methodB 호출]
methodA() → this.methodB() → 프록시 안 거침 → 트랜잭션 없음 ❌
```

### TransactionTemplate은 이 문제가 없다

TransactionTemplate은 프록시가 아니라 **직접 코드에서 트랜잭션을 연다.**
어디서 호출하든 상관없이 `.execute()` 블록 안은 항상 트랜잭션이다.

```java
public void methodA() {
    transactionTemplate.execute(status -> {
        // 여기는 항상 트랜잭션 ✅
        // methodA에서 호출하든, 외부에서 호출하든 상관없음
        return result;
    });
}
```

### 면접 Q&A

<details>
<summary><b>Q: Spring AOP의 self-invocation 문제를 설명해주세요.</b></summary>

<details>
<summary>답변 보기</summary>

@Transactional은 Spring AOP 프록시를 통해 동작합니다. 외부에서 호출하면 프록시가 가로채서 트랜잭션을 시작하지만, 같은 클래스 내부에서 this.method()로 호출하면 프록시를 거치지 않고 실제 객체를 직접 호출합니다. 그래서 @Transactional이 무시됩니다. StyleHub OAuth 로그인에서 googleLogin()이 같은 클래스의 findOrCreateUser()를 호출할 때 이 문제가 발생했고, 기존 유저 재로그인 시 더티 체킹이 안 되어 포인트가 DB에 반영되지 않는 버그가 있었습니다. TransactionTemplate으로 변경하여 해결했습니다.
</details>
</details>


<details>
<summary><b>Q: self-invocation 문제를 해결하는 방법은 어떤 것들이 있나요?</b></summary>

<details>
<summary>답변 보기</summary>

세 가지 방법이 있습니다. 첫째, 메서드를 별도 클래스로 분리하여 외부 호출로 만드는 방법입니다. 프록시가 동작하지만 클래스가 불필요하게 늘어납니다. 둘째, 자기 자신을 주입받아 프록시를 경유하는 self-injection인데, 순환 참조 위험이 있는 안티패턴입니다. 셋째, TransactionTemplate으로 프록시에 의존하지 않고 직접 트랜잭션을 관리하는 방법입니다. 프로젝트에서는 세 번째를 선택했습니다. 프록시 의존 없이 항상 동작하고, 기존 UserService와 패턴 일관성도 유지됩니다.
</details>
</details>


<details>
<summary><b>꼬리질문: Spring AOP에서 JDK 동적 프록시와 CGLIB 프록시의 차이는?</b></summary>

<details>
<summary>답변 보기</summary>

JDK 동적 프록시는 인터페이스 기반으로 프록시를 생성하고, 인터페이스를 구현한 클래스에만 적용 가능합니다. CGLIB 프록시는 클래스를 상속하여 프록시를 생성하고, 인터페이스 없이도 적용 가능합니다. Spring Boot는 기본적으로 CGLIB을 사용합니다. 둘 다 self-invocation 문제는 동일하게 발생합니다. 프록시 객체를 거치지 않는 내부 호출에서는 어떤 방식이든 AOP가 적용되지 않습니다.
</details>
</details>


<details>
<summary><b>꼬리질문: @Transactional 외에 AOP가 사용되는 대표적인 예는?</b></summary>

<details>
<summary>답변 보기</summary>

@Async(비동기 실행), @Cacheable(캐시), @Retryable(재시도), Spring Security의 @Secured(인가 체크) 등이 있습니다. 이 모든 어노테이션이 AOP 프록시로 동작하기 때문에, self-invocation 문제가 동일하게 적용됩니다. 같은 클래스에서 @Cacheable 메서드를 호출하면 캐시가 동작하지 않는 것도 같은 원리입니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 이 버그를 어떻게 발견했나요?</b></summary>

<details>
<summary>답변 보기</summary>

Postman으로 기존 OAuth 유저 재로그인을 테스트하다가 발견했습니다. 로그인은 성공하는데 DB를 확인해보니 pointBalance가 변하지 않았습니다. 신규 유저 생성은 save()를 명시적으로 호출하기 때문에 정상이었고, 기존 유저는 더티 체킹에 의존하는데 트랜잭션이 없어서 UPDATE가 안 된 것이었습니다. 이 경험으로 모든 분기(신규/기존)를 테스트해야 한다는 것을 배웠습니다.
</details>
</details>


---

## 9. JPA 더티 체킹 (Dirty Checking)

### 한 줄 요약
**트랜잭션 안에서 엔티티 필드를 바꾸면, 트랜잭션 끝날 때 자동으로 UPDATE 쿼리가 실행된다.**

### 예시: 자동 저장 문서

Google Docs에서 글을 수정하면 **자동 저장**된다. 별도로 "저장" 버튼을 안 눌러도 된다.

JPA도 마찬가지다:

```java
@Transactional
public void updateUser() {
    User user = userRepository.findById(1L);  // DB에서 가져옴
    user.setName("새이름");                     // 필드만 변경
    // save()를 호출하지 않아도 트랜잭션 커밋 시 자동으로 UPDATE 실행!
}
```

### 동작 원리

```
1. findById() → DB에서 User를 가져옴 → JPA가 "스냅샷"(원본 복사본)을 저장
2. user.setName("새이름") → 엔티티의 필드가 변경됨
3. 트랜잭션 커밋 시점 → JPA가 스냅샷과 현재 상태를 비교
4. 변경된 필드 발견 → UPDATE 쿼리 자동 생성 및 실행
```

```
[스냅샷]  name = "기존이름"
[현재]    name = "새이름"
→ 다르다! → UPDATE users SET name = '새이름' WHERE user_id = 1
```

### 더티 체킹이 동작하려면?

**반드시 트랜잭션 안에서** 실행되어야 한다.
트랜잭션이 없으면 커밋 시점이 없으므로 비교할 타이밍이 없다.

이것이 우리 OAuthService에서 발생한 버그의 원인이다:

```java
// self-invocation으로 @Transactional이 미동작
// → 트랜잭션 없음 → 더티 체킹 불가 → UPDATE 안 됨
user.rewardLoginPoint(LocalDate.now());  // 필드는 바뀌지만 DB에 반영 안 됨!
```

### 면접 Q&A

<details>
<summary><b>Q: JPA 더티 체킹은 어떻게 동작하나요?</b></summary>

<details>
<summary>답변 보기</summary>

엔티티를 조회하면 JPA가 그 시점의 상태를 스냅샷으로 저장합니다. 트랜잭션 커밋 시점에 현재 엔티티 상태와 스냅샷을 필드별로 비교하고, 변경된 필드가 있으면 자동으로 UPDATE 쿼리를 생성하여 실행합니다. save()를 호출하지 않아도 됩니다. 단, 반드시 트랜잭션 안에서 실행되어야 합니다. 트랜잭션이 없으면 커밋 시점이 없어서 비교가 일어나지 않습니다.
</details>
</details>


<details>
<summary><b>Q: 더티 체킹의 단점은 없나요?</b></summary>

<details>
<summary>답변 보기</summary>

모든 엔티티에 대해 스냅샷을 저장하므로 메모리를 추가로 사용합니다. 또한 커밋 시점에 모든 관리 상태 엔티티를 비교하므로 엔티티가 많으면 성능에 영향을 줄 수 있습니다. readOnly = true로 설정하면 스냅샷을 생성하지 않아 이 오버헤드를 제거할 수 있습니다. 변경이 없는 조회 전용 트랜잭션에서는 readOnly를 쓰는 것이 좋습니다.
</details>
</details>


<details>
<summary><b>꼬리질문: JPA 엔티티의 생명주기(영속성 컨텍스트 상태)를 설명해주세요.</b></summary>

<details>
<summary>답변 보기</summary>

4가지 상태가 있습니다. 비영속(new)은 new로 생성만 하고 영속성 컨텍스트에 등록 안 된 상태입니다. 영속(managed)은 save()나 find()로 영속성 컨텍스트에 관리되는 상태이고, 더티 체킹이 동작합니다. 준영속(detached)은 트랜잭션이 끝나거나 clear()로 영속성 컨텍스트에서 분리된 상태로, 더티 체킹이 안 됩니다. 삭제(removed)는 delete()로 삭제 예정인 상태입니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 준영속 상태의 엔티티를 다시 영속 상태로 만들려면?</b></summary>

<details>
<summary>답변 보기</summary>

merge()를 사용합니다. 준영속 엔티티의 식별자(ID)로 DB에서 영속 엔티티를 찾고, 준영속 엔티티의 값으로 덮어씌운 새로운 영속 엔티티를 반환합니다. 주의할 점은 merge()의 반환값이 원래 객체가 아닌 새로운 객체라는 것입니다. 원래 객체는 여전히 준영속 상태입니다.
</details>
</details>


<details>
<summary><b>꼬리질문: StyleHub에서 TransactionTemplate을 2개 나눴을 때 엔티티가 준영속 상태가 되지 않나요?</b></summary>

<details>
<summary>답변 보기</summary>

Spring Boot의 OSIV(Open Session In View) 기본 설정이 true이기 때문에, HTTP 요청 동안 Hibernate Session이 유지됩니다. 트랜잭션 1이 끝나도 세션은 살아있어서 엔티티가 영속 상태를 유지합니다. 트랜잭션 2에서 필드를 변경하면 더티 체킹이 동작합니다. 만약 OSIV를 끄면(spring.jpa.open-in-view=false) 트랜잭션 1 종료 시 엔티티가 준영속이 되어 트랜잭션 2에서 merge가 필요합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: OSIV를 켜두면 문제가 없나요?</b></summary>

<details>
<summary>답변 보기</summary>

OSIV를 켜면 컨트롤러, 뷰 렌더링까지 세션이 열려있어서 Lazy Loading이 편하지만, DB 커넥션도 그만큼 오래 점유됩니다. API 서버에서는 OSIV를 끄는 것이 권장되며, Spring Boot도 시작 시 OSIV 경고 로그를 출력합니다. StyleHub에서는 현재 기본값(true)을 사용하고 있지만, 프로덕션에서는 false로 변경하고 필요한 데이터를 트랜잭션 안에서 미리 로딩하는 것이 좋습니다.
</details>
</details>


---

## 10. 정적 팩토리 메서드

### 한 줄 요약
**`new` 대신 의미 있는 이름의 메서드로 객체를 생성한다.**

### 예시: 피자 주문

```
new Pizza(true, false, true, false)  → 뭐가 뭔지 모름
Pizza.hawaiian()                     → 하와이안 피자구나!
Pizza.pepperoni()                    → 페퍼로니 피자구나!
```

### 우리 코드

```java
// new User(...)를 직접 호출하지 않고:
User user = User.create("홍길동", "test@test.com", encodedPassword, birthDate);
User oauthUser = User.createOAuth("홍길동", "test@gmail.com", Provider.GOOGLE, "google-sub-123");
```

**장점:**
1. **이름으로 의도가 드러난다**: `create` = 일반 회원가입, `createOAuth` = OAuth 회원가입
2. **생성자를 숨긴다**: `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 `new User()`를 막음
3. **기본값이 자동 적용**: 빌더 내부에서 role=USER, grade=BRONZE 등이 설정됨

### 면접 Q&A

<details>
<summary><b>Q: 정적 팩토리 메서드를 사용한 이유는?</b></summary>

<details>
<summary>답변 보기</summary>

세 가지입니다. 첫째, 메서드 이름으로 생성 의도가 드러납니다. User.create()는 일반 회원가입, User.createOAuth()는 OAuth 가입이라는 것이 명확합니다. new User()는 어떤 방식의 생성인지 알 수 없습니다. 둘째, 생성자를 protected로 숨겨서 외부에서 new User()를 직접 호출하지 못하게 합니다. 반드시 팩토리 메서드를 통해 생성하도록 강제합니다. 셋째, 빌더 내부에서 @Builder.Default로 설정한 기본값(role=USER, grade=BRONZE)이 자동 적용됩니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 정적 팩토리 메서드와 빌더 패턴은 어떻게 다른가요?</b></summary>

<details>
<summary>답변 보기</summary>

정적 팩토리 메서드는 필수 파라미터만 받아서 객체를 생성합니다. 파라미터가 명확하고 변하지 않을 때 적합합니다. 빌더 패턴은 선택적 파라미터가 많을 때 적합합니다. StyleHub에서는 둘을 조합해서 사용합니다. User.create()는 정적 팩토리 메서드이지만, 내부적으로 User.builder()를 사용합니다. 외부에는 간결한 팩토리 메서드를 제공하고, 내부에서 빌더의 유연성을 활용하는 구조입니다.
</details>
</details>


<details>
<summary><b>꼬리질문: @NoArgsConstructor(access = AccessLevel.PROTECTED)는 왜 필요한가요?</b></summary>

<details>
<summary>답변 보기</summary>

JPA가 엔티티를 생성할 때 기본 생성자(no-args constructor)가 필요합니다. 하지만 public으로 열면 누구나 new User()로 불완전한 객체를 만들 수 있습니다. protected로 설정하면 JPA는 리플렉션으로 접근 가능하지만, 개발자가 직접 호출하는 것은 컴파일 타임에 방지됩니다. 반드시 User.create()나 User.createOAuth() 팩토리 메서드를 통해 생성하도록 강제합니다.
</details>
</details>


---

## 11. DTO (Data Transfer Object)

### 한 줄 요약
**계층 간 데이터를 주고받을 때 사용하는 전용 객체.**

### 예시: 택배 상자

엔티티(User)는 집 안의 모든 물건이다. 비밀번호, 포인트, 등급 등 전부 들어있다.
API 응답으로 집 안의 모든 물건을 보낼 수는 없다. 비밀번호가 노출된다.

**DTO는 택배 상자다.** 보낼 것만 골라서 담는다.

```
User (엔티티 — 집 안의 모든 물건):
  userId, name, email, password, role, grade, pointBalance, ...

UserLoginResponse (DTO — 택배 상자):
  userId, name, email, role
  → 비밀번호 없음! 필요한 것만!
```

### Request DTO vs Response DTO

```
[Request DTO]  클라이언트 → 서버로 보내는 데이터
UserSignUpRequest: name, email, password, birthDate

[Response DTO] 서버 → 클라이언트로 보내는 데이터
UserSignUpResponse: userId, name, email, birthDate
```

### 왜 엔티티를 직접 반환하면 안 되는가

1. **비밀번호 노출**: User 엔티티에는 password 필드가 있다
2. **API 스펙이 DB에 종속**: 엔티티 필드를 바꾸면 API 응답도 바뀜
3. **불필요한 데이터 전송**: 클라이언트가 필요 없는 totalSpent, pointBalance 등까지 전송

### 면접 Q&A

<details>
<summary><b>Q: 엔티티를 직접 API 응답으로 반환하면 안 되나요?</b></summary>

<details>
<summary>답변 보기</summary>

세 가지 문제가 있습니다. 첫째, 비밀번호 같은 민감 정보가 응답에 포함됩니다. 둘째, 엔티티 필드를 변경하면 API 스펙이 깨져서 프론트엔드가 함께 수정되어야 합니다. 셋째, 클라이언트가 필요 없는 데이터까지 전송됩니다. DTO를 사용하면 엔티티와 API 응답이 분리되어, 각각 독립적으로 변경할 수 있습니다.
</details>
</details>


<details>
<summary><b>Q: Request DTO와 Response DTO를 왜 분리하나요?</b></summary>

<details>
<summary>답변 보기</summary>

요청과 응답에 포함되는 필드가 다르기 때문입니다. 회원가입 요청에는 password가 있지만 응답에는 없습니다. 요청에는 birthDate가 있지만 응답에는 userId가 추가됩니다. 하나의 DTO로 합치면 요청 시에는 userId가 null이고, 응답 시에는 password가 null인 불완전한 객체가 됩니다.
</details>
</details>


<details>
<summary><b>꼬리질문: DTO 변환 로직(from 메서드)은 어디에 두는 게 좋은가요?</b></summary>

<details>
<summary>답변 보기</summary>

StyleHub에서는 Response DTO 안에 정적 메서드로 뒀습니다. `UserLoginResponse.from(user)`처럼 호출합니다. 다른 방법으로는 별도 Mapper 클래스를 만들거나 MapStruct 같은 라이브러리를 사용할 수 있습니다. 현재는 DTO가 단순하므로 from 메서드가 가장 간결합니다. 변환 로직이 복잡해지면 Mapper 분리를 고려할 수 있습니다.
</details>
</details>


---

## 12. Bean Validation

### 한 줄 요약
**요청 데이터가 올바른지 어노테이션으로 자동 검증한다.**

### 예시: 놀이공원 입장 게이트

```
키 120cm 이상 → 탑승 가능      = @Size(min = ...)
나이 확인 → 미성년자 제한        = @Past
티켓 확인 → 없으면 입장 불가     = @NotBlank
```

게이트(Validation)를 통과해야 놀이기구(서비스 로직)를 탈 수 있다.

### 우리 코드

```java
public record UserSignUpRequest(

        @NotBlank
        @Size(min = 2, max = 10)
        @Pattern(regexp = "^[가-힣a-zA-Z0-9]+$", message = "한글, 알파벳, 숫자만 허용됩니다")
        String name,

        @NotBlank
        @Email(message = "이메일 형식이 올바르지 않습니다")
        String email,

        @NotBlank
        @Size(min = 8, max = 15)
        @Pattern(regexp = "^(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,15}$",
                message = "비밀번호는 8~15자, 특수문자(@$!%*?&)를 포함해야 합니다")
        String password,

        @NotNull
        @Past(message = "생년월일은 과거 날짜여야 합니다")
        LocalDate birthDate
) {}
```

| 어노테이션 | 역할 | 실패 예시 |
|---|---|---|
| `@NotBlank` | 빈 문자열/null 방지 | `""`, `null` |
| `@Size(min=2, max=10)` | 길이 제한 | `"a"` (1글자) |
| `@Pattern` | 정규식 매칭 | `"test@user"` (특수문자) |
| `@Email` | 이메일 형식 | `"abc"` |
| `@Past` | 과거 날짜만 허용 | `"2099-01-01"` |

컨트롤러에서 `@Valid`를 붙이면 자동으로 검증된다:

```java
@PostMapping("/sign-up")
public ResponseEntity<UserSignUpResponse> signUp(@Valid @RequestBody UserSignUpRequest request) {
    // request가 여기에 도달했다면, 이미 모든 검증을 통과한 것
}
```

### 면접 Q&A

<details>
<summary><b>Q: Bean Validation을 사용하는 이유는? 서비스에서 직접 검증하면 안 되나요?</b></summary>

<details>
<summary>답변 보기</summary>

서비스에서 if문으로 검증해도 동작은 합니다. 하지만 Bean Validation을 쓰면 세 가지 이점이 있습니다. 첫째, 검증 로직이 DTO 선언부에 있어서 어떤 조건인지 한눈에 보입니다. 둘째, 컨트롤러에 @Valid만 붙이면 서비스 진입 전에 잘못된 요청을 걸러내서, 서비스 로직이 검증 코드로 오염되지 않습니다. 셋째, Spring이 MethodArgumentNotValidException을 자동으로 던져주므로 GlobalExceptionHandler에서 일관된 에러 응답을 할 수 있습니다.
</details>
</details>


<details>
<summary><b>Q: @NotBlank와 @NotNull의 차이는?</b></summary>

<details>
<summary>답변 보기</summary>

@NotNull은 null만 막습니다. 빈 문자열 ""은 통과합니다. @NotBlank는 null, 빈 문자열 "", 공백 문자열 "   " 모두 막습니다. String 필드에는 @NotBlank, LocalDate 같은 비문자열 필드에는 @NotNull을 사용합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: Bean Validation이 실패하면 어떤 예외가 발생하나요?</b></summary>

<details>
<summary>답변 보기</summary>

MethodArgumentNotValidException이 발생합니다. Spring MVC가 자동으로 던지며, GlobalExceptionHandler에서 @ExceptionHandler(MethodArgumentNotValidException.class)로 잡아서 400 Bad Request와 필드별 에러 메시지를 반환할 수 있습니다. 현재 StyleHub에서는 글로벌 예외 처리를 다음 PR에서 도입 예정이므로, 기본 Spring 에러 응답이 반환됩니다.
</details>
</details>


<details>
<summary><b>꼬리질문: @Valid와 @Validated의 차이는?</b></summary>

<details>
<summary>답변 보기</summary>

@Valid는 Jakarta Bean Validation 표준이고, 중첩 객체 검증을 지원합니다. @Validated는 Spring 전용으로, 그룹 기능을 추가로 제공합니다. 예를 들어 생성 시에는 비밀번호 필수, 수정 시에는 비밀번호 선택 같은 상황에서 그룹을 나눠서 검증할 수 있습니다. 대부분의 경우 @Valid로 충분합니다.
</details>
</details>


---

## 13. OAuth 2.0 (구글 로그인)

### 한 줄 요약
**"구글아, 이 사람이 누군지 대신 확인해줘"**

### 예시: 호텔 체크인

```
[일반 로그인]
호텔 프론트에서 직접 신분증 확인 → 체크인

[OAuth 로그인]
호텔: "경찰서에서 신원 확인서 받아오세요"
손님 → 경찰서(Google)에서 신원 확인 → 확인서(code) 발급
손님 → 호텔에 확인서 제출 → 호텔이 경찰서에 "이 확인서 진짜야?" 문의
경찰서: "네, 이름은 홍길동이고 이메일은 xxx@gmail.com입니다"
호텔 → 체크인 완료
```

### OAuth 2.0 플로우 (우리 코드 기준)

```
1단계: 프론트엔드가 구글 로그인 URL을 요청
   GET /api/v1/oauth/google
   → "https://accounts.google.com/o/oauth2/v2/auth?..." 반환

2단계: 사용자가 구글에서 로그인 + 동의

3단계: 구글이 우리 서버로 code를 보냄
   GET /api/v1/oauth/google/callback?code=4/0AQSTgQ...

4단계: 우리 서버가 code로 access_token 교환 (Google API 호출)
   code → Google → access_token 반환

5단계: access_token으로 유저 정보 조회 (Google API 호출)
   access_token → Google → {email, name, sub} 반환

6단계: DB에서 유저 조회/생성
   이메일로 검색 → 있으면 로그인, 없으면 회원가입
```

### 각 단계에서 교환되는 것

```
사용자의 구글 로그인
         ↓
   authorization code  ← 1회용, 5분 만료
         ↓
   access_token        ← 유저 정보를 조회할 수 있는 열쇠
         ↓
   유저 정보 (email, name, sub)
```

### 왜 code → access_token → 유저 정보로 3단계인가?

보안 때문이다.

- **code**: 브라우저 URL에 노출됨 → 1회용이고 5분 만료라 탈취돼도 안전
- **access_token**: 서버 간 통신으로만 교환 → 브라우저에 노출 안 됨
- code를 바로 유저 정보로 바꾸면, code가 탈취될 때 유저 정보도 바로 유출됨

### 면접 Q&A

<details>
<summary><b>Q: OAuth 2.0의 Authorization Code Grant 방식을 설명해주세요.</b></summary>

<details>
<summary>답변 보기</summary>

총 3단계입니다. 첫째, 사용자를 Google 인증 페이지로 리다이렉트합니다. 둘째, 사용자가 로그인하면 Google이 우리 서버의 callback URL로 authorization code를 보냅니다. 셋째, 서버가 이 code를 Google에 보내서 access_token으로 교환하고, access_token으로 유저 정보를 조회합니다. code는 1회용이고 5분 만료라 탈취돼도 안전하고, access_token은 서버 간 통신으로만 교환되어 브라우저에 노출되지 않습니다.
</details>
</details>


<details>
<summary><b>Q: Spring Security의 OAuth2 Client를 사용하지 않은 이유는?</b></summary>

<details>
<summary>답변 보기</summary>

Spring Security를 사용하지 않는 프로젝트이기 때문입니다. spring-boot-starter-oauth2-client를 추가하면 Spring Security의 자동 보안 설정이 함께 적용되어, 모든 엔드포인트에 인증이 필요해지는 등 불필요한 설정 작업이 생깁니다. OAuth2 플로우 자체는 HTTP 요청 3번이므로 RestClient로 직접 구현해도 충분히 간결합니다.
</details>
</details>


<details>
<summary><b>Q: 일반 회원가입 유저와 OAuth 유저의 이메일이 충돌하면 어떻게 처리하나요?</b></summary>

<details>
<summary>답변 보기</summary>

현재는 예외를 던져서 차단합니다. provider 필드가 null이면 일반 회원가입 유저이므로, 같은 이메일로 OAuth 로그인을 시도하면 "이미 일반 회원가입으로 등록된 이메일입니다"를 반환합니다. 프로덕션에서는 비밀번호 확인 후 기존 계정에 OAuth를 연결하는 계정 연동 방식도 있지만, 보안적으로 복잡하므로 현재는 차단 방식을 선택했습니다.
</details>
</details>


<details>
<summary><b>꼬리질문: OAuth에서 Google API 호출이 실패하면 어떻게 되나요?</b></summary>

<details>
<summary>답변 보기</summary>

현재는 RestClient가 4xx/5xx 응답을 받으면 RestClientException을 던지고, Spring이 500 에러로 응답합니다. 글로벌 예외 처리 도입 시 잘못된 code는 400 + "인증 코드가 유효하지 않습니다", Google 서버 장애는 503 + "외부 인증 서비스에 연결할 수 없습니다"로 분리할 예정입니다.
</details>
</details>


<details>
<summary><b>꼬리질문: Google API 호출을 트랜잭션 밖에서 하는 이유는?</b></summary>

<details>
<summary>답변 보기</summary>

Google API 호출은 외부 HTTP 통신으로 수백ms가 걸릴 수 있습니다. 이걸 트랜잭션 안에서 실행하면 BCrypt와 동일한 문제가 발생합니다. DB 커넥션이 수백ms 동안 불필요하게 점유됩니다. UserService의 BCrypt 최적화와 같은 원리로, 느린 작업(외부 API 호출)을 트랜잭션 밖에서 실행하고, DB 작업만 트랜잭션 안에서 실행합니다. 이 패턴은 S3 업로드, 이메일 발송, 결제 API 호출 등에도 동일하게 적용됩니다.
</details>
</details>


<details>
<summary><b>꼬리질문: OAuth access_token은 저장하나요?</b></summary>

<details>
<summary>답변 보기</summary>

StyleHub에서는 저장하지 않습니다. access_token은 유저 정보 조회에만 1회 사용하고 버립니다. Google Calendar, Drive 등 유저의 Google 서비스에 지속적으로 접근해야 한다면 저장이 필요하지만, 우리는 로그인 시 이메일/이름만 가져오면 되므로 저장할 이유가 없습니다. 불필요한 토큰 저장은 보안 위험만 증가시킵니다.
</details>
</details>


---

## 14. 엔티티 캡슐화

### 한 줄 요약
**엔티티의 상태를 바꾸는 판단은 엔티티 자신이 한다. 서비스가 대신 판단하지 않는다.**

### 예시: ATM

```
[캡슐화 안 된 ATM]
사용자: "잔고 얼마야?" → ATM: "50,000원이요"
사용자: "그럼 내가 직접 잔고를 30,000원으로 바꿀게" → ATM: "네..."
→ 아무나 잔고를 마음대로 바꿀 수 있다!

[캡슐화 된 ATM]
사용자: "20,000원 출금해줘" → ATM: "잔고 확인... 가능합니다. 처리했습니다."
→ ATM이 잔고 확인 + 차감을 직접 한다. 사용자는 "출금"만 요청한다.
```

### 우리 코드

```java
// 안 좋은 예: 서비스에서 직접 판단
if (user.getRole() != Role.ADMIN) {
    if (user.getLastLoginDate() == null) {
        user.setPointBalance(user.getPointBalance() + 1000);
    } else if (!user.getLastLoginDate().equals(today)) {
        user.setPointBalance(user.getPointBalance() + 10);
    }
    user.setLastLoginDate(today);
}

// 좋은 예: 엔티티가 스스로 판단 (우리 코드)
user.rewardLoginPoint(today);
```

서비스는 **"포인트 지급해"**라고만 명령하고,
**어떤 조건에서 얼마를 지급할지**는 User 엔티티가 결정한다.

```java
// User.java 내부
public void rewardLoginPoint(LocalDate today) {
    if (role == Role.ADMIN) return;                    // Admin 제외
    if (lastLoginDate == null) pointBalance += 1000;   // 첫 로그인
    else if (!lastLoginDate.equals(today)) pointBalance += 10;  // 일일 로그인
    lastLoginDate = today;
}
```

**장점:**
- 포인트 정책이 바뀌면 **User 엔티티만 수정**하면 됨
- 서비스에 setter가 노출되지 않음
- 어디서 호출하든 (UserService, OAuthService) **동일한 로직 보장**

### 면접 Q&A

<details>
<summary><b>Q: 포인트 로직을 왜 서비스가 아닌 엔티티에 넣었나요?</b></summary>

<details>
<summary>답변 보기</summary>

포인트 지급 여부는 User의 내부 상태(lastLoginDate, role)로 결정됩니다. 서비스에서 판단하면 getLastLoginDate(), getRole()로 상태를 꺼내서 if문으로 비교해야 하고, setter로 값을 직접 변경해야 합니다. 이러면 엔티티의 캡슐화가 깨지고, 동일한 로직이 UserService와 OAuthService에 중복됩니다. 엔티티 안에 두면 서비스는 rewardLoginPoint(today) 한 줄만 호출하고, 판단은 엔티티가 합니다. 정책이 바뀌면 엔티티 한 곳만 수정하면 됩니다.
</details>
</details>


<details>
<summary><b>Q: 엔티티에 비즈니스 로직을 넣는 것은 DDD에서 어떤 개념인가요?</b></summary>

<details>
<summary>답변 보기</summary>

도메인 주도 설계(DDD)에서 말하는 Rich Domain Model입니다. 엔티티가 데이터만 가진 빈혈 모델(Anemic Domain Model)이 아니라, 자신의 상태를 변경하는 행위까지 가지고 있습니다. 서비스는 도메인 객체들의 흐름을 조율하는 역할만 하고, 비즈니스 판단은 엔티티가 합니다. rewardLoginPoint()가 대표적인 예입니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 빈혈 모델(Anemic Domain Model)이란?</b></summary>

<details>
<summary>답변 보기</summary>

엔티티가 getter/setter만 가지고 비즈니스 로직은 전부 서비스에 있는 구조입니다. Martin Fowler가 안티패턴으로 지적했습니다. 엔티티가 단순 데이터 컨테이너로 전락하고, 서비스가 비대해지며, 동일한 로직이 여러 서비스에 중복됩니다. 반대로 Rich Domain Model은 엔티티가 자신의 행위를 가지고, 서비스는 흐름만 조율합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 모든 비즈니스 로직을 엔티티에 넣어야 하나요?</b></summary>

<details>
<summary>답변 보기</summary>

아닙니다. 엔티티의 상태에 의존하는 로직만 넣습니다. 예를 들어 "이 유저에게 포인트를 줄지 말지"는 유저의 상태(role, lastLoginDate)로 판단하므로 엔티티에 적합합니다. 하지만 "이메일 중복 체크"는 다른 유저들의 존재 여부를 확인해야 하므로 Repository에 의존하는 서비스/Validator에 두는 것이 맞습니다. 외부 의존성이 필요한 로직은 엔티티에 넣지 않습니다.
</details>
</details>


<details>
<summary><b>꼬리질문: 엔티티에 로직을 넣으면 테스트하기 어렵지 않나요?</b></summary>

<details>
<summary>답변 보기</summary>

오히려 쉬워집니다. 엔티티의 비즈니스 메서드는 외부 의존성이 없는 순수 자바 코드입니다. Spring Context나 Mock 없이 new User()로 객체를 만들고 rewardLoginPoint()를 호출하면 됩니다. 서비스에 로직이 있으면 Repository Mock, TransactionTemplate Mock 등이 필요하지만, 엔티티 메서드는 단위 테스트가 훨씬 간단합니다.
</details>
</details>


---

## 15. record (Java 14+)

### 한 줄 요약
**불변 데이터 객체를 한 줄로 만드는 문법.**

### 예시

```java
// 기존 class 방식: 25줄
public class UserLoginResponse {
    private final Long userId;
    private final String name;
    private final String email;

    public UserLoginResponse(Long userId, String name, String email) {
        this.userId = userId;
        this.name = name;
        this.email = email;
    }

    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    // equals, hashCode, toString도 직접 구현해야 함...
}

// record 방식: 1줄
public record UserLoginResponse(Long userId, String name, String email) {}
```

record가 자동으로 만들어주는 것:
- 모든 필드 `private final` (불변)
- 생성자
- getter (`userId()`, `name()`, `email()` — get 접두사 없음)
- `equals()`, `hashCode()`, `toString()`

### 왜 DTO에 record를 쓰는가

DTO는 **데이터를 담아서 전달하는 것**이 목적이다.
변경할 필요가 없고(불변), getter만 있으면 된다.
record가 정확히 이 용도에 맞다.

### 면접 Q&A

<details>
<summary><b>Q: record와 class의 차이는?</b></summary>

<details>
<summary>답변 보기</summary>

record는 Java 14에서 도입된 불변 데이터 전용 클래스입니다. 선언하면 모든 필드가 private final이 되고, 생성자, getter, equals(), hashCode(), toString()이 자동 생성됩니다. class는 필드 변경이 가능하고 이 모든 것을 직접 구현해야 합니다. DTO처럼 데이터를 담아서 전달하는 용도에는 record가 적합하고, 상태 변경이 필요한 엔티티에는 class가 적합합니다.
</details>
</details>


<details>
<summary><b>Q: record를 엔티티에 쓸 수 없나요?</b></summary>

<details>
<summary>답변 보기</summary>

쓸 수 없습니다. JPA 엔티티는 기본 생성자(no-args constructor)가 필요하고, setter로 필드를 변경할 수 있어야 합니다. record는 불변이라 setter가 없고, 기본 생성자도 제공하지 않습니다. 또한 JPA 프록시 생성을 위해 클래스가 final이면 안 되는데, record는 암묵적으로 final입니다.
</details>
</details>


<details>
<summary><b>꼬리질문: record에 @Builder를 같이 쓰는 이유는?</b></summary>

<details>
<summary>답변 보기</summary>

record의 기본 생성자는 모든 필드를 순서대로 받습니다. 필드가 많으면 어떤 값이 어떤 필드인지 헷갈립니다. @Builder를 붙이면 `.userId(1L).name("홍길동").email("test@test.com")`처럼 필드 이름을 명시하면서 생성할 수 있어 가독성이 좋아집니다. 특히 Response DTO의 from() 팩토리 메서드 안에서 빌더를 사용하면 어떤 필드에 어떤 값을 매핑하는지 명확합니다.
</details>
</details>


<details>
<summary><b>꼬리질문: Lombok의 @Builder와 record의 조합에서 주의할 점은?</b></summary>

<details>
<summary>답변 보기</summary>

record는 자체 생성자가 있기 때문에, Lombok @Builder가 만드는 빌더와 충돌할 수 있습니다. record에 @Builder를 사용하면 Lombok이 record의 canonical constructor를 기반으로 빌더를 생성합니다. 일반적으로 잘 동작하지만, 커스텀 생성자를 추가하면 충돌이 발생할 수 있어 주의가 필요합니다.
</details>
</details>


---

## 학습 순서 요약

```
1.  트랜잭션           → 다 되거나 다 안 되거나
2.  커넥션 풀          → 전화선 묶음, 고갈되면 전체 장애
3.  @Transactional    → 메서드 전체가 커넥션 점유
4.  BCrypt            → 의도적으로 느린 해싱, ~100ms
5.  문제 인식          → BCrypt + @Transactional = 커넥션 낭비
6.  TransactionTemplate → 블록 단위로 커넥션 제어
7.  로그인 3단계 구조   → 조회(TX1) → BCrypt(밖) → 포인트(TX2)
8.  AOP 프록시         → Spring이 만든 대리인
9.  더티 체킹           → 트랜잭션 안에서 필드 바꾸면 자동 UPDATE
10. 정적 팩토리 메서드   → new 대신 의미있는 이름의 생성 메서드
11. DTO               → 필요한 데이터만 담는 택배 상자
12. Bean Validation    → 어노테이션으로 입력값 자동 검증
13. OAuth 2.0          → 구글아 이 사람 누구야?
14. 엔티티 캡슐화       → 판단은 엔티티가, 명령은 서비스가
15. record             → 불변 데이터 객체 한 줄로 생성
```

이 순서대로 이해하면, 우리 코드의 **모든 설계 결정**을 설명할 수 있다.
