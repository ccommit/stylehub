# HTTP 세션 기반 인증 구현 정리

## 1. 왜 HTTP 세션 인증인가?

### 선택 이유
- JWT는 토큰 탈취 시 서버에서 즉시 무효화할 수 없다 (stateless 특성)
- HTTP 세션은 서버에서 세션을 직접 관리하므로 **즉시 무효화(로그아웃, 강제 로그아웃)가 가능**하다
- Spring Boot가 기본 제공하는 `HttpSession`을 사용하여 별도 라이브러리 없이 구현 가능하다
- 추후 대용량 트래픽 대응 시 Spring Session + Redis로 확장이 용이하다

### 세션 vs JWT 비교
| 구분 | HTTP 세션 | JWT |
|------|-----------|-----|
| 저장 위치 | 서버 메모리 | 클라이언트 |
| 즉시 무효화 | 가능 (session.invalidate()) | 불가능 (만료까지 대기) |
| 서버 확장 | Redis 세션 저장소 필요 | 별도 저장소 불필요 |
| 보안 | 서버 관리, 상대적 안전 | 토큰 탈취 위험 |

---

## 2. 전체 아키텍처

```
클라이언트 → [쿠키: JSESSIONID] → AuthInterceptor → RoleCheckInterceptor → Controller
                                      ↓ (실패)           ↓ (실패)
                                   401 UNAUTHORIZED    403 FORBIDDEN
```

### 구성 요소
| 구성 요소 | 파일 | 역할 |
|-----------|------|------|
| SessionConstants | common/constants/SessionConstants.java | 세션 attribute key 상수 관리 |
| UserController | user/controller/UserController.java | 로그인 시 세션 생성, 로그아웃 시 세션 무효화 |
| AuthInterceptor | common/config/AuthInterceptor.java | 세션 존재 여부 검증 (인증) |
| RoleCheckInterceptor | common/config/RoleCheckInterceptor.java | @RequiredRole 기반 역할 검증 (인가) |
| RequiredRole | common/config/RequiredRole.java | 역할 지정 커스텀 어노테이션 |
| WebConfig | common/config/WebConfig.java | 인터셉터 등록 및 경로 설정 |
| SessionUtils | common/util/SessionUtils.java | 세션에서 사용자 정보 조회 유틸 |
| ErrorCode | common/exception/ErrorCode.java | UNAUTHORIZED, FORBIDDEN, SESSION_EXPIRED |

---

## 3. 실행 플로우

### 3-1. 로그인 플로우 (일반 로그인)

```
클라이언트                           서버
   |                                  |
   |  POST /api/v1/users/login        |
   |  {"email":"..","password":".."}   |
   | -------------------------------->|
   |                                  |  1. UserService.login() 호출
   |                                  |     - 트랜잭션 내: 이메일로 사용자 조회
   |                                  |     - 트랜잭션 밖: BCrypt 비밀번호 검증 (DB 커넥션 점유 방지)
   |                                  |     - 트랜잭션 내: LoginEvent 발행 (포인트 지급)
   |                                  |     - UserLoginResponse 반환
   |                                  |
   |                                  |  2. createSession() 호출 (컨트롤러 레이어)
   |                                  |     - 기존 세션 무효화 (Session Fixation 방지)
   |                                  |     - 새 세션 생성
   |                                  |     - userId, role 저장
   |                                  |
   |  200 OK                          |
   |  Set-Cookie: JSESSIONID=abc123   |
   |  {"userId":1,"name":"..","role":..}
   | <--------------------------------|
```

**왜 세션 생성을 컨트롤러에서 하는가?**
- 서비스 레이어는 비즈니스 로직에 집중해야 한다
- `HttpServletRequest`는 웹 계층의 관심사이므로 서비스에 넘기면 계층 분리가 깨진다
- 서비스의 트랜잭션이 완료된 후 세션을 생성해야 한다 (트랜잭션 실패 시 세션 생성 방지)

### 3-2. 로그인 플로우 (Google OAuth)

```
클라이언트                        서버                         구글
   |                               |                            |
   |  GET /oauth/google            |                            |
   | ----------------------------->|                            |
   |  {"authorizationUrl":"..."}   |                            |
   | <-----------------------------|                            |
   |                               |                            |
   |  구글 로그인 페이지 이동       |                            |
   | --------------------------------------------------------->|
   |  인증 완료, code 발급          |                            |
   | <---------------------------------------------------------|
   |                               |                            |
   |  GET /oauth/google/callback   |                            |
   |  ?code=xyz                    |                            |
   | ----------------------------->|                            |
   |                               |  1. 구글에 code로 토큰 요청  |
   |                               | -------------------------->|
   |                               |  access_token 반환          |
   |                               | <--------------------------|
   |                               |                            |
   |                               |  2. 토큰으로 사용자 정보 요청 |
   |                               | -------------------------->|
   |                               |  이메일, 이름 반환           |
   |                               | <--------------------------|
   |                               |                            |
   |                               |  3. DB에서 사용자 조회/생성
   |                               |  4. LoginEvent 발행
   |                               |  5. createSession() — 우리 서버 세션 생성
   |                               |
   |  200 OK                       |
   |  Set-Cookie: JSESSIONID=def456|
   | <-----------------------------|
```

**왜 OAuth 로그인에도 세션을 발급하는가?**
- 구글 OAuth는 "이 사람이 구글 사용자 맞다"를 확인하는 **1회성 인증 수단**이다
- 이후 우리 서비스의 API를 호출할 때는 **우리 서버의 인증 상태**가 필요하다
- 세션이 없으면 매 요청마다 구글에 다시 인증해야 한다
- 구글은 "누군지 확인"만 해주고, 로그인 상태 유지는 우리 서버 책임이다

### 3-3. 인증이 필요한 API 요청 플로우

```
클라이언트                                    서버
   |                                           |
   |  GET /api/v1/some-api                     |
   |  Cookie: JSESSIONID=abc123                |
   | ---------------------------------------->|
   |                                           |
   |                            [AuthInterceptor.preHandle()]
   |                                           |
   |                            세션 존재? → JSESSIONID로 세션 조회
   |                            userId 존재? → session.getAttribute("SESSION_USER_ID")
   |                                           |
   |                            ┌── 없음 → throw BusinessException(UNAUTHORIZED) → 401
   |                            └── 있음 → 통과
   |                                           |
   |                            [RoleCheckInterceptor.preHandle()]
   |                                           |
   |                            @RequiredRole 어노테이션 있음?
   |                            ┌── 없음 → 통과 (역할 검증 불필요)
   |                            └── 있음 → 세션의 role과 비교
   |                                  ┌── 불일치 → throw BusinessException(FORBIDDEN) → 403
   |                                  └── 일치 → 통과
   |                                           |
   |                            [Controller 실행]
   |                                           |
   |  200 OK + 응답 데이터                      |
   | <----------------------------------------|
```

### 3-4. 로그아웃 플로우

```
클라이언트                           서버
   |                                  |
   |  POST /api/v1/users/logout       |
   |  Cookie: JSESSIONID=abc123       |
   | -------------------------------->|
   |                                  |  session.invalidate()
   |                                  |  → 서버 메모리에서 세션 삭제
   |                                  |  → JSESSIONID 쿠키 무효화
   |  200 OK                          |
   | <--------------------------------|
   |                                  |
   |  이후 요청 시 세션 없음 → 401     |
```

---

## 4. 세션 고정 공격(Session Fixation) 방지

### 공격 시나리오
```
1. 공격자가 서버에 접속 → JSESSIONID=attacker123 발급받음
2. 공격자가 피해자에게 JSESSIONID=attacker123 쿠키를 심어둠 (XSS, 링크 등)
3. 피해자가 해당 쿠키로 로그인
4. 서버가 기존 세션(attacker123)에 userId, role 저장
5. 공격자가 JSESSIONID=attacker123으로 요청 → 피해자로 인증됨
```

### 방지 방법 (우리 코드)
```java
private void createSession(HttpServletRequest request, Long userId, UserRole role) {
    // 기존 세션 무효화 → 공격자의 세션 ID가 무효화됨
    HttpSession oldSession = request.getSession(false);
    if (oldSession != null) {
        oldSession.invalidate();
    }
    // 새 세션 생성 → 새로운 JSESSIONID 발급
    HttpSession newSession = request.getSession(true);
    newSession.setAttribute(SessionConstants.SESSION_USER_ID, userId);
    newSession.setAttribute(SessionConstants.SESSION_USER_ROLE, role);
}
```
로그인 성공 시 기존 세션을 무효화하고 새 세션을 생성하므로, 공격자가 미리 심어둔 세션 ID는 더 이상 유효하지 않다.

---

## 5. 인터셉터 경로 설정

### WebConfig 설정
```java
registry.addInterceptor(authInterceptor)
        .addPathPatterns("/api/**")              // 모든 API에 인증 적용
        .excludePathPatterns(
                "/api/v1/users/sign-up",          // 회원가입
                "/api/v1/users/login",            // 로그인
                "/api/v1/users/oauth/**"          // OAuth 전체 경로
        );

registry.addInterceptor(roleCheckInterceptor)
        .addPathPatterns("/api/**");              // 모든 API에 역할 검증 적용
```

**왜 이렇게 설정했는가?**
- `AuthInterceptor`는 인증 전 경로(로그인, 회원가입, OAuth)를 반드시 제외해야 한다. 하나라도 빠뜨리면 로그인 자체가 불가능해진다.
- `RoleCheckInterceptor`는 모든 경로에 적용하되, `@RequiredRole` 어노테이션이 없으면 자동으로 통과한다. 역할 검증이 필요한 메서드에만 어노테이션을 붙이면 된다.
- `AuthInterceptor` → `RoleCheckInterceptor` 순서로 실행되므로, 인증 실패 시 역할 검증까지 가지 않는다.

### @RequiredRole 사용 예시
```java
// ADMIN만 접근 가능
@RequiredRole(UserRole.ADMIN)
@GetMapping("/admin/stores")
public ResponseEntity<?> getStores() { ... }

// STORE 또는 ADMIN 접근 가능
@RequiredRole({UserRole.STORE, UserRole.ADMIN})
@PostMapping("/stores/{storeId}/products")
public ResponseEntity<?> createProduct() { ... }

// 어노테이션 없음 → 로그인한 사용자 모두 접근 가능
@GetMapping("/products")
public ResponseEntity<?> getProducts() { ... }
```

---

## 6. 세션 설정 (application.properties)

```properties
server.servlet.session.timeout=30m           # 세션 유효시간 30분
server.servlet.session.cookie.http-only=true # JavaScript에서 쿠키 접근 차단 (XSS 방지)
server.servlet.session.cookie.same-site=lax  # CSRF 공격 방지 (같은 사이트 요청만 쿠키 전송)
```

| 설정 | 값 | 이유 |
|------|-----|------|
| timeout | 30m | 30분 미활동 시 자동 로그아웃. 보안과 사용성의 균형 |
| http-only | true | XSS 공격으로 JavaScript가 세션 쿠키를 탈취하는 것을 방지 |
| same-site | lax | 외부 사이트에서 우리 서버로 요청 시 쿠키를 보내지 않아 CSRF 방지 |

---

## 7. 쿠키와 세션의 관계

```
[클라이언트]                              [서버 메모리]
┌─────────────────┐                 ┌──────────────────────────┐
│ Cookie:          │                │ 세션 저장소                 │
│ JSESSIONID=abc123│  ─── 매칭 ──→  │ abc123: {                  │
│ (세션 ID만 저장)  │                │   SESSION_USER_ID: 1,      │
│                  │                │   SESSION_USER_ROLE: USER   │
└─────────────────┘                │ }                           │
                                   │ def456: {                   │
                                   │   SESSION_USER_ID: 2,       │
                                   │   SESSION_USER_ROLE: ADMIN   │
                                   │ }                            │
                                   └──────────────────────────────┘
```

- **쿠키**: 세션 ID(JSESSIONID)만 저장하는 운반 수단
- **세션**: 실제 사용자 정보(userId, role)가 저장되는 서버 측 저장소
- 클라이언트는 세션 ID만 알고, 실제 데이터는 서버에만 존재한다

---

## 8. 추후 확장 포인트

### Redis 세션 저장소 전환
현재는 톰캣 메모리 세션을 사용하므로 서버 1대에서만 유효하다.
대용량 트래픽으로 서버를 스케일아웃할 경우 Redis 세션 저장소로 전환이 필요하다.

```
[현재] 서버 1대 — 톰캣 메모리 세션
[추후] 서버 N대 — Spring Session + Redis (세션 공유)
```

전환 시 코드 변경은 최소화된다:
1. `spring-session-data-redis` 의존성 추가
2. `application.properties`에 Redis 설정 추가
3. `@EnableRedisHttpSession` 어노테이션 추가
4. 기존 `HttpSession` 코드는 그대로 유지 (Spring Session이 투명하게 대체)
