## 들어가며

현재 진행중인 StyleHub 프로젝트는 상품 주문 , 결제, 선착순 쿠폰 발급, 재고 동시성 제어에 중점을 둔 대용량 백엔드 서버를 만드는것을 목표로 했다. 

쇼핑몰(무신사)의 모든 기능을 다 구현하기보다 핵심에 집중하기 위해 나머지는 최소 비용으로 안전하게 만드는 것을 기준으로 삼았다. 

인증 인가도 그중 하나였다. 

필수 기능이지만 이 프로젝트의 차별화 포인트는 아니다. 

그래서 스프링 시큐리티나 화려한 구조보다는 핵심 기능에 집중하기 위해 구현 비용은 낮추고 필요한 수준의 보안만 만족하는 방식을 선택했다.

여러 기술을 비교 후 고민끝에  HTTP 세션 인증 방식을 선택했다. 

처음에는   구현비용이 가장 낮다고 생각했지만 구현 과정은 쉽지만은 않았다. 

세션 발급 위치, 트랜잭션과, BCript의 관계, 인증과 인가의 분리, 세션 고정 공격 대응 등 라이브러리를 쓰면 고민하지 않아도 될 문제들을 직접 결정해야 했다. 

이제 인증 방식을 어떻게 선택했고, 어떤 기준으로 결정했으며, 실제 구현 과정에서 어떤 고민을 했는지 정리해보려 한다.

---

### 0\. 구현 비용을 기준으로 선택한 인증 방식

인증 방식을 결정하면서 가장 중요한 기준은 어떤 방식이 더 좋아 보이는가가 아니라 핵심 기능에 집중하기 위해 얼마나 적은 비용으로 충분한 안정성을 확보할수 있는가였다. 

이 기준에서 보면 JWT는 생각보다 비용이 크다고 판단했다. 

서명 알고리즘, 시크릿키 관리, 토큰 만료, 리프레시 정책, 탈취 대응까지 직접 설계 해야 할 요소가 많다. 

반면 HTTP 세션은 서블릿 컨테이너가 기본 구현을 제공한다. 

우리는  세션에 무엇을 담고 어떻게 검증할지만 결정하면 된다. 

---

### 1\. 인증과 인가를 분리하다 — 두 개의 인터셉터

> 기능을 합치면 코드는 짧아지지만, 책임을 분리하면 시스템은 견고해진다. 

로그인 이후 매 요청은 

세션이 발급된 다음, 매 요청마다 두 가지를 검증해야 한다.

1.  **인증(Authentication)**: 이 요청이 로그인된 사용자에게서 온 게 맞는가?
2.  **인가(Authorization)**: 이 사용자가 이 API를 호출할 자격(역할)이 있는가?

처음에는 하나의 인터셉터에서 모두 처리하는 방식을 고려했다.  
구조가 단순하고 흐름을 한눈에 파악할 수 있기 때문이다.

하지만 이 방식에는 두 가지 문제가 있었다.  
 **1.할 검사가 필요 없는 API에서도 불필요한 인가 로직이 매번 실행된다.**  
 **2.인증 실패(401)와 인가 실패(403)가 하나의 책임 안에 섞이면서 역할이 모호해진다.**

이 문제를 해결하기 위해 인증과 인가를 분리하고 각각을 별도의 인터셉터로 구성했다.

-   **AuthInterceptor**: 세션 존재 여부만 검증 → 실패 시 401
-   **RoleCheckInterceptor**: @RequiredRole 기반 권한 검증 → 실패 시 403

이렇게 분리하면 각 요청은 필요한 검증만 수행하게 되고 응답 코드의 의미도 명확하게 유지된다.

또한 이 구조는 확장에 유리하다. 

인증 방식이 추가되거나 권한 체계가 변경되더라도 기존 로직을 수정하지 않고 인터셉터를 교체하거나 확장하는 방식으로 대응할 수 있다.

결과적으로 인증과 인가를 분리한 것은 단순한 구현상의 선택이 아니라 **책임을 명확히 나누고 확장 가능한 구조를 만들기 위한 설계 결정**이었다.

---

**요청 흐름은 다음과 같다.** 

```
요청 → AuthInterceptor (인증 — 세션 존재 여부)
       → 실패 시 401 UNAUTHORIZED
    → RoleCheckInterceptor (인가 — @RequiredRole 검증)
       → 실패 시 403 FORBIDDEN
    → Controller
```

-   요청이 들어 오면 
-   AuthInterceptor가 세션 존재 여부를 검증한다 (인증)
    -   실패 시 401 UNAUTHORIZED
-   RoleCheckInterceptor가 권한을 검증한다 (인가)'
    -   \- @RequiredRole이 있는 경우에만 수행
    -   실패 시 403 FORBIDDEN
-   모든 검증을 통과하면 Controller로 전달된다

---

**인증 인터셉터 — 세션 기반 검증**

```
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

인증 인터셉터는 세션 기반으로 로그인 여부만 검증한다.  
요청에 세션이 존재하고 userId가 저장되어 있는지를 확인하는 구조다.

여기서 세 가지를 고려했다.

-   getSession(false) 사용  
    세션이 없을 때 새로 생성하지 않도록 했다. 인증 검사를 위해 세션을 만드는 것은 의미가 어긋나기 때문이다.
-   HandlerMethod 체크  
    정적 리소스 요청까지 인증 로직이 적용되는 것을 방지했다.


-   세션 값 검증  
    세션이 존재하더라도 userId가 없으면 인증되지 않은 요청으로 판단한다.

---

**인가 인터셉터 — 어노테이션 기반 권한 검사**

```
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

인가 인터셉터는 **@RequiredRole 어노테이션을 기준으로 권한을 검증**한다.  
어노테이션이 존재하는 경우에만 인가 검사를 수행하고, 없으면 요청을 그대로 통과시킨다.

여기서 세 가지를 고려했다.

-   **어노테이션 존재 여부 기반 실행**  
    모든 API에 권한 검사를 적용하지 않고, 필요한 경우에만 수행하도록 했다.


-   **메서드 레벨 우선**  
    메서드에 선언된 어노테이션이 클래스보다 우선하도록 처리해 세밀한 권한 제어가 가능하도록 했다.


-   **인증 이후 인가 검증**  
    세션과 사용자 역할을 확인한 뒤, 요구 권한과 일치하지 않으면 403을 반환한다.

---

**설계 시 고려한  부분** 

**1\. getSession(false) 사용**  
세션이 없을 때 새로 생성하지 않도록 했다.  
인증 검사를 위해 세션을 생성하는 것은 의미가 어긋나기 때문이다.

**2\. HandlerMethod 체크**  
정적 리소스 요청까지 인증 로직이 적용되는 것을 방지했다.

**3\. 메서드 레벨 어노테이션 우선**  
클래스보다 메서드의 설정이 우선되도록 처리했다. 이는 Spring의 @Transactional과 동일한 관례를 따른 것이다.

---

**컨트롤러 사용 예시**

```
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

**확장성애 대하여** 

@RequiredRole 어노테이션은 UserRole\[\] 배열을 받도록 정의해뒀다.

새로운 권한이 필요해지면 어노테이션만 확장하면 된다. 

-   UserRole enum 값 추가
-   어노테이션 수정

인터셉터 코드는 수정할 필요가 없다.

즉 이 구조는 확장에는 열려있고 수정에는 닫혀 있는 형태를 유지한다. 

---

### 2\. 세션 고정 공격  대응 :  로그인할 때마다 세션 ID를 새로 발급한다

세션 기반 인증에서 대표적인 취약점은 세션 고정 공격이다. 

공격자가 미리 생성한 세션 ID를 피해자에게 주입하고 피해자가 로그인하면 해당 세션이 인증된 상태가 되는 방식이다.

공격흐름은 다음과 같다. 

```
1. 공격자가 우리 서버에 접속 → JSESSIONID=ATTACKER_ID 발급받음
2. 공격자가 피해자에게 ATTACKER_ID를 심는다 (XSS, URL 파라미터 등)
3. 피해자가 그 세션 ID로 우리 사이트에 들어와 로그인
4. 서버: "어, 세션이 있네. 여기에 userId 저장하자" → ATTACKER_ID에 피해자 userId 저장
5. 공격자가 ATTACKER_ID 쿠키로 요청 → 피해자로 인증됨
```

이 시나리오에서 **서버는 정상 동작하고 있다.** 어떤 검증도 실패하지 않는다. 그래서 더 무서운 공격인것 같다. 

이 공격에 대응하기 위해  **로그인이 성공한 순간, 기존 세션은 무조건 폐기하고 새로운 세션 ID를 발급**하도록 하였다.

공격자가 심어둔 세션 ID는 로그인과 동시에 무효화된다.

```
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

기존 세션 ID는 즉시 무효화되고 새로운 세션 ID가 발급되므로 공격자가 심어둔 세션은 사용할 수 없게 된다.

여기에 더해 세션 쿠키 자체의 보안 옵션도 함께 걸었다.

```
server.servlet.session.timeout=30m            # 30분 미활동 시 자동 만료
server.servlet.session.cookie.http-only=true  # JavaScript에서 쿠키 접근 차단 (XSS 방어)
server.servlet.session.cookie.same-site=lax   # 외부 사이트 → 우리 서버 요청에 쿠키 미전송 (CSRF 방어)
```

`http-only`는 XSS로 `document.cookie`를 읽어가는 것을, `same-site=lax`는 CSRF 공격에서 외부 사이트가 자동으로 쿠키를 실어보내는 것을 막는다.

단, 사용자가 직접 클릭한 링크 같은 top-level GET 요청에는 쿠키가 허용되기 때문에 GET을 상태 변경에 사용하지 않는다는 전제가 있어야 CSRF 방어로서 의미가 있다. 완전한 방어를 위해서는 CSRF 토큰을 함께 쓰는 것이 일반적이다.

**세션 고정, XSS, CSRF — 세 가지 공격이 서로 다른 방어선에서 차단**되도록 했다.

---

**설계 원칙 — 보안 로직의 중앙화**

세션 생성, 조회, 무효화 로직은 SessionUtils로 분리했다. 컨트롤러는 해당 유틸만 호출하도록 제한했다.

이렇게 하면 모든 세션 생성 과정에서 **세션 재발급 로직이 항상 동일하게 적용된다.**

만약 각 컨트롤러에서 세션을 직접 생성했다면 어느 한 곳에서 invalidate() 호출을 빠뜨리는 순간 취약점이 발생할 수 있다.

결과적으로 **보안 로직은 한 곳에 모을수록 안전하다**는 원칙을 적용했다.

---

### 3\. 401/403과 일관된 응답 처리

인증과 인가를 분리하면서, 각각의 실패 상황을 어떻게 응답으로 표현할지도 함께 고민했다.

인터셉터에서 직접 응답을 작성하는 방식도 가능하지만 이 경우 컨트롤러에서 발생하는 예외와 응답 포맷이 달라지는 문제가 생긴다.

그래서 인터셉터에서도 응답을 직접 만들지 않고 **BusinessException을 던져 글로벌 예외 처리로 위임하는 방식을 선택했다.**

```
401 UNAUTHORIZED  { "code": "UNAUTHORIZED",  "message": "로그인이 필요합니다" }
403 FORBIDDEN     { "code": "FORBIDDEN",     "message": "권한이 없습니다" }
```

이렇게 하면 인터셉터 코드는 **"무엇을 검증할 것인가"** 에만 집중하고 응답 포맷·로깅·에러 코드 매핑은 전부 `GlobalExceptionHandler`로 위임된다. 한 가지 책임만 가진 인터셉터가 되니 더 짧고 더 안전해졌다.

---

### 4\. OAuth와 자체 세션은 어떻게 만나는가

마지막으로 OAuth 로그인을 추가하면서, 외부 인증을 사용하더라도 우리 서버 세션이 필요한지 고민했고 필요하다고 판단했다.

OAuth는 로그인 시점에 “이 사람이 누구인가”를 증명하는 역할이고

이후 요청에서는 “우리 서비스에 로그인한 사용자”임을 유지해야 한다.

그래서 OAuth로 인증되더라도 이후 요청 처리는 동일하게 세션 기반으로 가져갔다.

```
UserLoginResponse loginResult = userService.login(request);
SessionUtils.createSession(...);
```

결과적으로 인증 방식이 달라도 세션 처리 로직은 그대로 유지된다.

---

## 마치며 

이번 인증 기능은  단순한 구조를 선택했지만 그 안에서 트랜잭션, 커넥션, 보안까지 직접 고민해야 했다.

이 과정을 통해 기능 구현보다 **설계 기준을 세우는 것이 더 중요하다는 걸 체감했다.**

앞으로도 “어떻게 만들까”보다 **“어디에 집중해야 할까”를 먼저 고민하는 개발자가 되고자 한다.**