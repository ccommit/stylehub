# HTTP 세션 인증 직접 구현 — 의도적 단순성과 그 안에서의 7가지 의사결정

## 왜 이 프로젝트의 인증 방식을 "의도적으로 단순하게" 결정했나

StyleHub는 **상품 주문, 결제, 선착순 쿠폰 발급 — 대용량 트래픽 시나리오를 견디는 백엔드**가 목표인 프로젝트다. 이 목표를 정의하는 순간, 모든 모듈의 설계 기준이 단일 원칙으로 수렴한다.

> **"가장 잘 만든다"가 아니라 "핵심에 시간과 설계 역량을 쓸 수 있도록 기반 기능은 가장 작은 비용으로 충분히 안전하게 만든다."**

인증·인가는 어떤 시스템이든 필요한 기반 기능이지만, 이 프로젝트의 차별화 포인트가 아니다. 그래서 인증 모듈은 **구현 비용을 최소화하면서도 보안 요구를 만족하는 선택**이 무엇인지부터 정해야 했다. 후보는 셋 — JWT 직접 구현, Spring Security, HTTP 세션 — 이었고, 비교 결과 HTTP 세션을 채택했다.

그러나 "단순한 선택"이 "쉬운 작업"은 아니다. 라이브러리(Spring Security)를 쓰지 않기로 결정한 순간, 다음 7개 의사결정을 직접 풀어야 했다.

1. **JWT가 아닌 HTTP 세션을 채택한 근거** — 즉시 무효화 가능성, 매 요청 외부 I/O 비교
2. **세션 발급의 책임 위치** — 컨트롤러 vs 서비스
3. **BCrypt를 트랜잭션 밖으로 빼는 결정** — TransactionTemplate 채택
4. **인증과 인가의 분리** — 두 인터셉터 구조
5. **세션 고정 공격 방어** — 로그인 시점의 세션 ID 재발급
6. **인증 예외의 응답 일관성** — BusinessException + GlobalExceptionHandler
7. **OAuth와 자체 세션의 통합** — 인증 출처와 세션 발급의 분리

이 7개 결정은 모두 **"라이브러리가 자동으로 처리해주는 것을 직접 답한 결과"** 이며, 각각의 결정에 명시적 근거가 있다. 이 글은 그 의사결정의 기록이다 — "어떻게 만들었는가"보다 "왜 그렇게 결정했는가"에 무게를 둔다.

---

## 본문

### 의사결정 1 — 왜 JWT가 아닌 HTTP 세션인가

신입 백엔드 포트폴리오의 통념적 답은 거의 항상 JWT다. "stateless", "MSA에 어울린다", "확장이 쉽다" 같은 문구들이 그 통념을 떠받친다. 그러나 통념을 채택하기 전에, 이 프로젝트의 인증 도메인에 던져야 하는 더 본질적인 질문이 있다.

> **"우리 도메인은 즉시 무효화가 필요한가, 그리고 매 요청에서 외부 I/O를 어디까지 허용하는가?"**

이 질문에 답하면 통념과 다른 결론이 나올 수 있다. 결제·환불·주문 같은 민감한 행위가 많은 도메인에서는 **사용자가 비밀번호를 바꿨거나 의심스러운 활동이 감지되면 즉시 로그아웃시켜야 한다**. 이 요구가 채택 후보의 본질적 적합성을 가른다.

| 비교 항목 | HTTP 세션 | JWT 단독 | JWT + 블랙리스트 |
|---|---|---|---|
| 즉시 무효화 | O (`session.invalidate()`) | **X** | O (Redis 조회) |
| 매 요청 외부 I/O | O (세션 조회) | X | O (블랙리스트 조회) |
| 직접 구현 비용 | 낮음 (서블릿 기본 제공) | 높음 (서명·만료·리프레시 정책) | 매우 높음 |
| 확장 시 코드 변경 | 의존성 한 줄 + 어노테이션 한 줄 | 그대로 | 그대로 |

이 표의 결정적 시사: **즉시 무효화 요구를 받아들이면 "JWT의 stateless 명분"은 어차피 무너진다.** 매 요청마다 블랙리스트를 조회해야 하므로, 외부 저장소 조회 횟수는 HTTP 세션과 동일해진다. 그렇다면 처음부터 상태(세션)를 명시적으로 가져가는 것이 **더 정직한 설계**다.

또한 직접 구현 비용 측면에서도 차이가 명확하다. JWT는 직접 구현할 경우 서명 알고리즘, 시크릿 키 관리, 액세스/리프레시 토큰 분리, 만료 처리, 탈취 대응까지 정책을 직접 결정해야 한다. 반면 `HttpSession`은 **서블릿 컨테이너가 구현체를 제공하므로, 우리가 책임지는 영역은 "세션에 무엇을 담고 어떻게 검증할 것인가"** 로 한정된다.

결과적으로 `HttpSession`을 채택했다. 이 선택은 **현재의 단순성**과 **미래의 확장성**을 동시에 만족한다.

- **현재**: 의존성 0개, 표준 서블릿 API 위에서 동작
- **미래**: 트래픽이 늘어 서버를 스케일아웃할 때, `spring-session-data-redis` 의존성과 `@EnableRedisHttpSession` 한 줄로 **Redis 세션 저장소로 무중단 전환** 가능. 우리 코드는 자바 표준 `HttpSession` 인터페이스에만 의존하므로 구현체 교체에 영향받지 않는다

이 선택은 통념(JWT)을 따르지 않은 결정이지만, 도메인의 본질(즉시 무효화 필요)과 프로젝트 우선순위(핵심 기능에 시간 투자)를 동시에 만족시키는 답이다.

### 의사결정 2 — 세션 발급의 책임 위치: 컨트롤러 vs 서비스

`POST /users/login` 설계 시 첫 분기점은 **세션 발급의 책임 위치**다. 두 후보를 평가한다.

| 후보 | 구조 | 평가 |
|------|------|------|
| **(A) 서비스가 직접 발급** | 서비스가 `HttpServletRequest`를 인자로 받아 인증+세션 발급을 한 메서드에서 처리 | ❌ 웹 계층 객체가 도메인 계층 침투 |
| **(B) 컨트롤러가 발급** | 서비스는 인증만, 컨트롤러가 결과를 받아 세션 발급 | ✓ 웹 프로토콜과 비즈니스 로직 분리 |

후보 A의 결정적 문제는 **`HttpServletRequest`라는 웹 계층 객체가 서비스에 들어오는 순간 헥사고날 아키텍처가 무너진다**는 점이다. 단위 테스트에서 `MockHttpServletRequest`를 만들어야 하고, 세션 동작을 mock해야 하며, 서비스가 웹 프레임워크에 종속된다. 짧은 코드의 대가가 너무 크다.

후보 B는 책임을 자연스럽게 분리한다.

- `UserService.login(req)` — "이 사람이 진짜 가입된 사용자인가"라는 비즈니스 질문에만 답
- `SessionUtils.createSession()` — "그렇다면 세션을 발급한다"는 웹 프로토콜의 일을 처리

```java
// 실제 코드 — UserController.login()
@PostMapping("/users/login")
public ResponseEntity<UserLoginResponse> login(
        @Valid @RequestBody UserLoginRequest request,
        HttpServletRequest httpRequest) {
    UserLoginResponse loginResult = userService.login(request);                          // ① 인증
    SessionUtils.createSession(httpRequest, loginResult.userId(), loginResult.role());   // ② 세션 발급
    return ResponseEntity.ok(loginResult);
}
```

또 하나 중요한 부수 효과는 **트랜잭션 커밋 후에 세션이 발급된다**는 점이다. 서비스가 예외로 롤백됐는데 세션이 이미 만들어져 있다면, 인증 상태와 DB 상태가 어긋나는 가장 위험한 종류의 버그가 발생한다. 위 구조에서는 `userService.login()`이 정상 반환된 다음에야 세션이 만들어지므로, **서비스의 예외가 자연스럽게 세션 발급을 막는다**. 호출 순서 한 줄에 "트랜잭션 커밋 후 세션 발급"이라는 원칙이 강제로 새겨진다.

### 의사결정 3 — BCrypt를 트랜잭션 밖으로: 커넥션 점유 시간 = 시스템 처리량 상한

스프링에서 로그인 메서드를 짤 때 통념적 답은 **메서드 전체에 `@Transactional`을 붙이고 그 안에서 사용자 조회와 비밀번호 검증을 함께 처리하는 구조**다. 코드 리뷰에서도 무난히 통과할 모양이지만, 이 구조를 **"DB 커넥션 점유 시간"의 관점**에서 다시 분석하면 결정적 문제가 드러난다.

```
@Transactional 진입 → HikariCP에서 커넥션 획득 (setAutoCommit(false))
  ├── findByEmail()           ~3ms   ← 커넥션 사용 중
  ├── passwordEncoder.matches() ~80ms ← 커넥션은 잡힌 채, CPU만 쓰는 중
  └── return                          ← 커넥션 반환
```

BCrypt 비교 연산은 **DB와 한 글자도 통신하지 않는 순수 CPU 연산**이다. 그러나 `@Transactional`의 생명주기에 묶이면 80ms 동안 커넥션을 점유한다. HikariCP 기본 풀이 10개라면 다음 등식이 성립한다.

```
커넥션 점유 시간 ≥ 80ms (BCrypt) + 3ms (DB)
시스템 처리량 상한 ≈ 풀 크기 / 점유 시간 = 10 / 83ms ≈ 120 req/s
```

그리고 결정적인 함정은 **이 풀이 전체 시스템 공유 자원**이라는 점이다. 동시 로그인 100건이 들어오면 90명이 커넥션을 못 잡고 대기하며, **그동안 상품 조회·주문·결제 모든 API가 같은 풀에서 자원을 받지 못한다**. 즉 인증 도메인의 비용이 도메인 안에 격리되지 않고 시스템 전체로 전파되어 **로그인 폭주 = 전체 서비스 장애**라는 구조가 된다.

### 트랜잭션 분리 후보의 비교

해결책은 BCrypt를 트랜잭션 밖으로 빼는 것이다. 후보 셋을 평가했다.

| 후보 | 메커니즘 | 평가 |
|------|---------|------|
| 같은 클래스 내 메서드 분리 + @Transactional | self-invocation으로 어노테이션 우회 | ❌ Spring AOP 프록시 함정으로 동작 불능 |
| 별도 클래스 분리 | 트랜잭션 메서드만 다른 빈에 분리 | ❌ 단순 조회 한 줄을 위해 클래스 신설 |
| Self-injection (`@Lazy UserService self`) | 자기 자신을 주입받아 프록시 경유 호출 | ❌ 순환 참조 안티패턴, 가독성 저하 |
| **TransactionTemplate** | 코드에서 트랜잭션 범위를 직접 표현 | ✓ 범위가 시각적으로 명시 |

**TransactionTemplate 채택의 결정적 근거**: `.execute(...)` 블록 안쪽이 트랜잭션, 바깥쪽이 트랜잭션 밖이라는 사실이 **코드 표면에 그대로 드러난다**. 다른 후보들은 "어노테이션이 적용되는가"를 추론해야 하지만, TransactionTemplate은 추론이 필요 없다. **추상화의 메커니즘을 가독성으로 명시**하는 것이 보안·정합성에 영향을 주는 코드의 권장 원칙이다.

```java
// 실제 코드 — UserService.login()
public UserLoginResponse login(UserLoginRequest request) {
    User user = Objects.requireNonNull(
            transactionTemplate.execute(status ->
                    userRepository.findByEmail(request.email())
                            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PASSWORD))
            )
    );

    if (!passwordHasher.matches(request.password(), user.getPassword())) {
        throw new BusinessException(ErrorCode.INVALID_PASSWORD);
    }

    if (user.getRole() == UserRole.USER) {
        rewardLoginPoint(user.getUserId(), LocalDate.now());
    }

    return UserLoginResponse.from(user);
}
```

`transactionTemplate.execute(...)` 블록 안쪽만 트랜잭션이고, 그 바깥의 `passwordHasher.matches()`는 커넥션 없이 실행된다.

커넥션 점유 타임라인이 이렇게 바뀐다.

```
[변경 전] 커넥션 ── findByEmail(3ms) ── BCrypt(80ms) ── 반환    총 점유 ~83ms
[변경 후] 커넥션 ── findByEmail(3ms) ── 반환                    총 점유 ~3ms
                                  BCrypt(80ms, 커넥션 없음)
```

회원가입 BCrypt 부하 테스트 결과(커넥션 풀 5개 / 동시 100요청 / 타임아웃 500ms):

| | 변경 전 | 변경 후 |
|---|---|---|
| 성공 | 10 / 100 | **100 / 100** |
| 타임아웃 | 90건 | 0건 |

### 이 최적화의 진짜 가치 — 응답 속도가 아니라 장애 격리

이 최적화의 본질은 응답 속도가 아니다. BCrypt 자체는 여전히 80ms 걸린다. 가치는 다음 한 줄에 응축된다.

> **인증 도메인의 비용 모델이 시스템 전체 가용성에 영향을 주지 않도록 만든다.**

이커머스에서 로그인 폭주는 마케팅 이벤트마다 일어나는 일상적 사건이다. 그때 상품 조회와 결제까지 멈추면 매출이 멈춘다. 즉 이 결정은 **로컬한 최적화처럼 보이지만 실제로는 장애 영향 범위(blast radius) 축소**라는 시스템 안정성의 영역이다.

### 흔한 대안에 대한 답 — 왜 `@Transactional(readOnly = true)`로는 부족한가

자주 나오는 대안인 `@Transactional(readOnly = true)`가 이 문제를 풀지 못하는 이유를 명확히 한다.

| 측면 | `@Transactional(readOnly=true)` | TransactionTemplate (BCrypt 외부) |
|------|------------------------------|-------------------------------|
| 더티 체킹 비용 | 제거됨 | 제거됨 (조회만) |
| **커넥션 점유 시간** | **메서드 진입~종료 전체 (~83ms)** | **DB 작업만 (~3ms)** |
| 시스템 처리량 상한 | 동일 (~120 req/s) | 약 30배 향상 |

**핵심**: `readOnly = true`는 트랜잭션의 **성격**을 바꾸지만 **범위**는 바꾸지 않는다. 본질적 병목(커넥션 점유 시간)을 직접 다루지 않는 최적화는 처리량 한계를 바꾸지 못한다.

### 의사결정 4 — 인증과 인가를 두 인터셉터로 분리

세션이 발급된 다음, 매 요청마다 두 가지를 검증해야 한다.

1. **인증(Authentication)**: 이 요청이 로그인된 사용자에게서 온 게 맞는가? → 실패 시 401
2. **인가(Authorization)**: 이 사용자가 이 API를 호출할 자격(역할)이 있는가? → 실패 시 403

이 두 검증을 **하나의 인터셉터에 통합할지, 분리할지**가 의사결정 지점이다.

| 측면 | 통합 (1개 인터셉터) | 분리 (2개 인터셉터) |
|------|------------------|------------------|
| 코드 흐름 가독성 | 한눈에 보임 | 흐름이 두 클래스에 분산 |
| 책임 명확성 | 401/403 분기가 한 메서드 내 | 각 인터셉터가 단일 응답 코드 |
| 인가 불필요 API | **모든 API에서 어노테이션 체크 실행** | 어노테이션 없으면 인가 검사 자체 skip |
| HTTP 표준 명확성 | 401/403 처리가 뒤엉킴 | 각자 단일 책임 |

분리를 선택했다. 결정적 근거는 **"인가 검사가 필요 없는 API에서까지 어노테이션 체크 코드를 매번 실행하는 비효율"** 과 **"단일 책임 원칙을 따른 응답 코드 매핑의 명확성"** 이다.

```
요청 → AuthInterceptor (인증 — 세션 존재 여부)
       → 실패 시 401 UNAUTHORIZED
    → RoleCheckInterceptor (인가 — @RequiredRole 검증)
       → 실패 시 403 FORBIDDEN
    → Controller
```

```java
// 실제 코드 — AuthInterceptor.preHandle() : "세션이 있는가?"
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod)) {
        return true;  // 정적 리소스는 통과
    }

    HttpSession session = request.getSession(false);
    if (session == null || session.getAttribute(SessionConstants.SESSION_USER_ID) == null) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
    return true;
}
```

```java
// 실제 코드 — RoleCheckInterceptor.preHandle() : "어노테이션이 있을 때만 검증"
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod handlerMethod)) {
        return true;
    }

    // 메서드 레벨 우선, 없으면 클래스 레벨 확인
    RequiredRole requiredRole = handlerMethod.getMethodAnnotation(RequiredRole.class);
    if (requiredRole == null) {
        requiredRole = handlerMethod.getBeanType().getAnnotation(RequiredRole.class);
    }
    if (requiredRole == null) {
        return true;  // 어노테이션 없으면 인가 검사 자체를 안 함
    }

    HttpSession session = request.getSession(false);
    if (session == null) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    UserRole userRole = (UserRole) session.getAttribute(SessionConstants.SESSION_USER_ROLE);
    if (userRole == null) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    if (!hasRequiredRole(requiredRole.value(), userRole)) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    return true;
}
```

### 인터셉터 구현의 세 가지 디테일

이 코드는 단순해 보이지만 세 군데에 의식적인 결정이 들어 있다.

1. **`request.getSession(false)` 사용** — `true`로 호출하면 세션이 없을 때 새로 만들어 버린다. **인증 검사를 위해 세션을 생성하는 것은 의미가 역전된다** — 빈 세션을 만들어놓고 "userId가 없으니 UNAUTHORIZED"라고 던지는 모순이 발생. `false`로 호출해 세션 부재 자체를 검증한다

2. **`handler instanceof HandlerMethod` 체크** — 정적 리소스(이미지, JS 등)는 `HandlerMethod`가 아니다. 이 체크 없이 통과시키면 정적 리소스에도 세션 검사가 걸려 **로그인 페이지를 띄우는 것 자체가 막히는 부트스트래핑 문제**가 발생

3. **메서드 레벨 어노테이션 우선** — `@RequiredRole(USER)`가 클래스에 붙어 있어도 특정 메서드의 `@RequiredRole(ADMIN)`이 우선. Spring `@Transactional`과 동일한 관례를 따랐다. **API 설계의 일관된 관례를 따르는 것이 호출자의 직관 비용을 0으로 만든다** — 도구는 이미 익숙한 컨벤션을 따를 때 가장 작은 학습 비용을 가진다

실제 컨트롤러에서의 사용 모습은 이렇게 의도가 그대로 코드에 드러난다.

```java
// 일반 회원 전용 — 주문 생성 (OrderController)
@PostMapping("/orders")
@RequiredRole(UserRole.USER)
public ResponseEntity<OrderResponse> createOrder(...) { ... }

// 스토어 전용 — 자신의 스토어 조회 (UserController)
@GetMapping("/stores/my")
@RequiredRole(UserRole.STORE)
public ResponseEntity<StoreResponse> getMyStore(...) { ... }

// 관리자 전용 — 스토어 승인 (UserController)
@PatchMapping("/admin/stores/{storeId}/approve")
@RequiredRole(UserRole.ADMIN)
public ResponseEntity<StoreResponse> approve(...) { ... }
```

`@RequiredRole` 어노테이션은 `UserRole[]` 배열을 받도록 정의해뒀다. 현재는 단일 역할만 쓰지만, "스토어 또는 관리자 접근 가능" 같은 복합 권한이 등장하면 배열로 확장만 하면 된다. **어노테이션 시그니처 단계에서 확장성을 미리 열어두는 작은 결정**이 향후 변경 비용을 0에 가깝게 만든다.

이 구조의 진짜 가치는 **변경 시나리오에서 드러난다**. 새 역할 `MANAGER`를 추가하는 경우:

| 변경 항목 | 영향 |
|---------|------|
| `UserRole` enum에 값 추가 | 1줄 |
| 어노테이션 사용 변경 | 해당 컨트롤러 메서드만 |
| **인터셉터 코드** | **0줄 (변경 없음)** |

OCP("확장에는 열려있고 수정에는 닫혀있다")의 어노테이션 기반 구현이다.

### 의사결정 5 — 세션 고정 공격 방어: 로그인 시점의 세션 ID 재발급

세션 인증의 가장 유명한 취약점. **공격자가 미리 세션 ID를 만들어 피해자에게 심어두면, 피해자가 로그인했을 때 그 세션이 인증된 상태가 된다.**

```
1. 공격자가 우리 서버에 접속 → JSESSIONID=ATTACKER_ID 발급받음
2. 공격자가 피해자에게 ATTACKER_ID를 심는다 (XSS, URL 파라미터 등)
3. 피해자가 그 세션 ID로 우리 사이트에 들어와 로그인
4. 서버: "어, 세션이 있네. 여기에 userId 저장하자" → ATTACKER_ID에 피해자 userId 저장
5. 공격자가 ATTACKER_ID 쿠키로 요청 → 피해자로 인증됨
```

이 시나리오에서 **서버는 정상 동작하고 있다.** 어떤 검증도 실패하지 않는다. 그래서 발견이 더 어렵다 — 로그·알람·테스트 어디에서도 이상이 보이지 않는다.

방어 원리: **로그인이 성공한 순간 기존 세션을 무조건 폐기하고 새 세션 ID를 발급한다.** 공격자가 심어둔 세션 ID는 로그인과 동시에 무효화된다. 이 한 줄 원칙이 세션 고정 공격의 모든 변종을 막는다.

```java
public static void createSession(HttpServletRequest request, Long userId, UserRole role) {
    // 1. 기존 세션 폐기 — 공격자가 심어둔 세션 ID가 있다면 여기서 무효화
    HttpSession oldSession = request.getSession(false);
    if (oldSession != null) {
        oldSession.invalidate();
    }
    // 2. 새 세션 발급 — 새 JSESSIONID가 Set-Cookie로 응답에 실린다
    HttpSession newSession = request.getSession(true);
    newSession.setAttribute(SessionConstants.SESSION_USER_ID, userId);
    newSession.setAttribute(SessionConstants.SESSION_USER_ROLE, role);
}
```

여기에 더해, 세션 쿠키 자체의 보안 옵션도 함께 걸었다.

```properties
# 실제 코드 — application.properties
# Session
server.servlet.session.timeout=30m
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=lax
```

세 줄 각각의 의미는 이렇다.

- `timeout=30m` — 30분 미활동 시 세션 자동 만료. 보안과 사용성의 균형점이라 일반적인 웹 서비스의 기본값을 따랐다.
- `http-only=true` — JavaScript에서 `document.cookie`로 세션 쿠키에 접근할 수 없게 막는다. **XSS 공격으로 세션을 탈취하는 경로 차단**.
- `same-site=lax` — 외부 사이트에서 우리 서버로 요청할 때 쿠키를 자동으로 실어보내지 않게 한다. **CSRF 공격 방어**.

### 다층 방어 — 서로 다른 공격이 서로 다른 방어선에서 차단되는 구조

| 공격 종류 | 방어선 | 메커니즘 |
|---------|------|--------|
| 세션 고정 | 코드 레벨 (SessionUtils) | 로그인 시 `oldSession.invalidate()` |
| XSS로 세션 탈취 | 쿠키 속성 | `http-only=true` (JS 접근 차단) |
| CSRF | 쿠키 속성 | `same-site=lax` (외부 사이트 자동 전송 차단) |

**다층 방어 원칙**: 단일 메커니즘은 단일 종류의 공격만 막는다. 보안은 **여러 방어선이 서로 다른 공격 벡터에 대응**하도록 구성될 때 견고해진다. 어떤 한 방어선이 우회되어도 다음 방어선이 받쳐준다.

### `SessionUtils`로 세션 로직 단일화 — "쉬운 길이 안전한 길"

세션 생성/조회/무효화 로직은 `SessionUtils` 작은 유틸 클래스에 모두 모았다. 컨트롤러는 `SessionUtils.createSession(...)` 한 줄만 호출한다.

이 단일화의 결정적 가치: 만약 컨트롤러마다 `request.getSession(true).setAttribute(...)`를 직접 쓰는 코드가 있었다면, **어느 한 컨트롤러가 `oldSession.invalidate()`를 빠뜨리는 순간 그 API는 세션 고정에 뚫린다**. 세션 고정 방어는 모든 진입점에서 일관되게 적용되어야 가치가 있고, 단 한 곳의 누락이 전체 방어를 무력화한다.

원칙: **보안 로직은 한 곳에 모이고, 그 한 곳을 호출하는 것이 가장 쉬운 길이 되어야 한다.** 직접 우회할 길이 없는 API가 가장 안전한 API다 — "쉬운 길이 안전한 길"이라는 보안 설계의 핵심 원리.

### 의사결정 6 — 401/403 응답 포맷의 일관성

인증 실패는 401, 인가 실패는 403이라는 HTTP 표준 자체는 자명하다. 진짜 의사결정 지점은 **이 응답을 어떻게 일관되게 생성할 것인가**다.

흔한 방식은 인터셉터에서 `response.setStatus(401)` + `response.getWriter().write(...)`로 응답을 직접 쓰는 것이다. 짧고 직관적이지만 두 가지 본질적 문제가 있다.

| 문제 | 결과 |
|------|------|
| JSON 직렬화·ContentType을 직접 챙겨야 함 | 한 곳이라도 누락되면 깨진 응답 |
| 컨트롤러의 `BusinessException`과 응답 포맷이 분리 | **같은 백엔드인데 API마다 401 응답 모양이 다름** |

두 번째 문제가 결정적이다 — **클라이언트 입장에서 응답 포맷의 일관성은 API 사용성의 핵심**이다. 인증 실패가 어디서 발생하든 동일한 구조의 에러 응답을 받아야 클라이언트의 에러 처리가 단순해진다.

채택한 방식: **인터셉터에서도 컨트롤러와 똑같이 `BusinessException`을 던진다.** Spring MVC의 인터셉터는 `DispatcherServlet` 안에서 동작하므로, `preHandle`에서 던진 RuntimeException은 디스패처로 전파되어 `HandlerExceptionResolver`가 글로벌 핸들러로 라우팅한다. **인터셉터의 예외 = 컨트롤러의 예외**, 같은 처리 경로를 탄다.

```
401 UNAUTHORIZED  { "code": "UNAUTHORIZED",  "message": "로그인이 필요합니다" }
403 FORBIDDEN     { "code": "FORBIDDEN",     "message": "권한이 없습니다" }
```

이 결정의 부수 효과: 인터셉터 코드는 **"무엇을 검증할 것인가"** 만 남고, 응답 포맷·로깅·에러 코드 매핑은 전부 `GlobalExceptionHandler`로 위임된다. 단일 책임 원칙을 따르는 인터셉터가 자연스럽게 만들어진다 — 보안 검증의 본질에만 집중할 수 있는 구조다.

### 의사결정 7 — OAuth와 자체 세션의 통합: 인증 출처와 세션 발급의 분리

구글 OAuth 로그인 추가 시 의사결정 지점: **OAuth로 인증된 사용자에게도 우리 서버 세션을 발급해야 하는가?** 구글이 이미 인증해줬는데 또 세션을 만드는 것이 중복으로 보일 수 있다.

이 질문에 답하려면 **인증과 세션 유지의 책임을 명확히 분리**해야 한다.

| 단계 | 누가 책임지는가 | 무엇을 증명하는가 |
|---|---|---|
| **인증** (로그인 시점) | 구글 (OAuth) | "이 사람이 google 계정 X의 주인이다" |
| **세션 유지** (이후 모든 API 요청) | 우리 서버 | "방금 우리 서비스에 로그인한 그 사람이다" |

**핵심 통찰**: OAuth는 **1회성 인증 수단**일 뿐, 이후 우리 서비스 API 호출마다 구글에 다시 물어볼 수는 없다. 매 요청마다 구글 API를 호출하면 응답 시간이 2~3초 추가되며, 외부 시스템 의존성이 우리 서비스의 가용성을 직접 결정하게 된다.

따라서 인증 출처(구글 vs 비밀번호)는 다르지만 **세션 발급 이후의 관리는 동일**해야 한다. OAuth 콜백을 일반 로그인과 완전히 같은 구조로 설계했다.

```java
// 실제 코드 — UserController : 일반 로그인
@PostMapping("/users/login")
public ResponseEntity<UserLoginResponse> login(
        @Valid @RequestBody UserLoginRequest request,
        HttpServletRequest httpRequest) {
    UserLoginResponse loginResult = userService.login(request);
    SessionUtils.createSession(httpRequest, loginResult.userId(), loginResult.role());
    return ResponseEntity.ok(loginResult);
}

// 실제 코드 — UserController : OAuth 콜백
@GetMapping("/users/oauth/{provider}/callback")
public ResponseEntity<OAuthLoginResponse> callback(
        @PathVariable OAuthProvider provider,
        @RequestParam String code,
        HttpServletRequest httpRequest) {
    OAuthLoginResponse loginResult = oAuthService.login(provider, code);
    SessionUtils.createSession(httpRequest, loginResult.userId(), loginResult.role());
    return ResponseEntity.ok(loginResult);
}
```

**원리**: 인증 출처는 다양해질 수 있지만(구글, 카카오, 비밀번호, 이메일 링크 등), **세션 발급 이후의 관리는 단일 메커니즘으로 통합**된다. `userService.login()` vs `oAuthService.login()`은 다르지만, `SessionUtils.createSession()`은 동일하다. 인증 방식이 5개로 늘어나도 세션 관리 코드는 그대로다.

이 분리가 의미하는 본질: **"변하는 부분(인증 출처)"과 "변하지 않는 부분(세션 관리)"을 분리한다.** 추상화의 가장 기본적 원칙이 여기서 작동한다 — 변경 압력이 다른 두 영역을 같은 위치에 두지 않는다.

---

## 정리 — "의도적 단순성" 안에서의 7가지 의사결정

이 인증 모듈은 **"가장 멋있게 만든 모듈"이 아니라 "가장 의도적으로 단순하게 만든 모듈"** 이다. 대용량 트래픽 서버라는 프로젝트 목표가 있을 때, 시간과 설계 역량을 어디에 쓸지의 우선순위가 명확해진다 — 동시성 제어, 트랜잭션 범위, 재고 락, Redis 큐 같은 핵심 기능에 깊이를 줘야 한다. 인증·인가에 화려한 구조를 도입하는 것은 **"포트폴리오를 위한 설계"** 이지 **"프로젝트를 위한 설계"** 가 아니다.

그러나 단순한 선택이 곧 안일한 작업은 아니다. HTTP 세션이라는 단순한 선택을 내린 다음, 라이브러리가 자동 처리해주던 7가지를 직접 결정해야 했다.

| # | 의사결정 | 핵심 근거 |
|---|---------|---------|
| 1 | JWT 대신 HTTP 세션 | 즉시 무효화 요구 + 매 요청 외부 I/O 비교 |
| 2 | 세션 발급 책임은 컨트롤러 | 헥사고날 계층 분리 + 트랜잭션 후 발급 |
| 3 | BCrypt를 TransactionTemplate으로 | 커넥션 점유 시간 = 시스템 처리량 상한 |
| 4 | 인증/인가 두 인터셉터 | 단일 책임 + 응답 코드 명확성 |
| 5 | 로그인 시점 세션 ID 재발급 | 세션 고정 + 다층 방어 |
| 6 | BusinessException 통일 | 응답 포맷 일관성 + 인터셉터 단일 책임 |
| 7 | SessionUtils로 세션 로직 단일화 | 인증 출처와 세션 관리 분리 |

이 7가지 결정의 공통점: **모두 "라이브러리가 자동 처리해주던 것을 직접 답한 결과"** 다. 라이브러리를 쓰면 이 질문들을 만나지 않지만, 직접 답한 경험이 있으면 다음에 Spring Security를 채택하더라도 **"왜 이렇게 설계되어 있는가"** 가 보이며, 다른 보안 결정에서도 동일한 종류의 질문을 빠르게 던질 수 있다.

### 현재 구현의 한계와 미리 검증한 확장 경로

현재 인증 모듈은 톰캣 인메모리 세션 위에서 동작한다. **단일 서버에서는 충분하지만, 스케일아웃 시점에 세션 공유가 깨진다**는 명확한 한계가 있다. 이 한계를 알면서도 현재 구조로 종료한 이유는 단순하다 — **`spring-session-data-redis` 의존성과 `@EnableRedisHttpSession` 한 줄로 무중단 전환 가능함을 사전 검증**했기 때문이다.

우리 코드는 자바 표준 `HttpSession` 인터페이스에만 의존하므로 구현체(톰캣 → Redis) 교체에 영향받지 않는다. 즉 **"지금은 핵심에 집중하기 위해 단순하게, 확장 시점이 오면 그대로 늘어날 수 있도록"** 이라는 초기 의도가 코드 구조 차원에서 보장된다.

> 실제 다중 인스턴스 환경 측정과 Spring Session 전환 검증은 [선착순쿠폰-측정여정.md](선착순쿠폰-측정여정.md)에서 수행됐다. 측정 부산물로 다중 인스턴스 환경의 세션 외부화 필요성이 정량적으로 드러났고, 그 결정이 즉시 반영됐다.
