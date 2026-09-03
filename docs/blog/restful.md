# URL 하나에 담긴 설계 의도 — StyleHub의 RESTful API 설계기

## 왜 URL 설계부터 잡고 시작했나

StyleHub는 사용자, 판매자, 어드민이라는 **세 종류의 호출 주체**가 같은 자원(상품, 주문, 쿠폰)을 서로 다른 권한으로 다루는 이커머스 플랫폼이다. 도메인 다이어그램을 그리던 단계에서부터 한 가지가 명확해졌다.

> 호출 주체 × 자원 × 액션의 조합이 곧 API 개수가 된다. 이 조합이 수십 개로 늘어났을 때 일관된 규칙이 없으면, **매번 URL 짓는 데 들어가는 인지 비용**이 그대로 개발 속도와 협업 비용으로 환산된다.

URL은 단순한 문자열이 아니라 **시스템의 외부 계약(contract)** 이다. 한번 노출되면 클라이언트, 모니터링 도구, 게이트웨이, 캐시 정책이 모두 그 위에서 동작하기 때문에, 뒤늦게 바꾸는 비용이 다른 어떤 리팩터링보다 크다. 그래서 컨트롤러를 첫 줄 작성하기 전에 **설계 원칙부터 합의**하고 들어가기로 했다.

선택한 기준이 RESTful이었다. 다만 "REST 원칙을 무조건 따르자"가 아니라, **각 원칙을 우리 도메인에 적용했을 때 얻는 가치와 잃는 가치를 사례별로 따져보자**가 출발점이었다. 이 글은 그 판단의 기록이다.

---

## RESTful이 뭔데

REST(Representational State Transfer)는 Roy Fielding이 2000년 박사학위 논문에서 제안한 **분산 시스템 아키텍처 스타일**이다. RESTful은 그 원칙을 잘 따르는 것을 뜻한다.

핵심 6가지 제약 조건이 있는데, 우리가 매일 부딪히는 건 그중에서도 다음 4가지다.

### 1. 자원(Resource) 중심

URL은 **명사**여야 한다. 동사가 아니다.

```
❌ POST /createUser
❌ GET  /getProducts
✅ POST /users
✅ GET  /products
```

URL이 가리키는 건 "할 일"이 아니라 "대상(자원)"이다. 무엇을 할지는 HTTP 메서드가 결정한다.

### 2. HTTP 메서드의 의미를 살린다

| 메서드 | 의미 | 멱등성 | 안전성 |
|--------|------|--------|--------|
| GET    | 조회 | ✅ | ✅ |
| POST   | 생성, 비멱등 동작 | ❌ | ❌ |
| PUT    | 전체 교체 | ✅ | ❌ |
| PATCH  | 부분 수정 | ❌ | ❌ |
| DELETE | 삭제 | ✅ | ❌ |

**멱등성(idempotent)**: 같은 요청을 여러 번 보내도 결과가 같다. 네트워크 재시도가 자유롭다.
**안전성(safe)**: 서버 상태를 변경하지 않는다.

이게 중요한 이유는, 클라이언트(브라우저, CDN, 프록시)가 이 약속을 믿고 동작하기 때문이다. GET 요청은 캐싱하고, PUT/DELETE는 안전하게 재시도한다.

### 3. 계층적 자원 구조

자원에 소속 관계가 있으면 URL에 드러낸다.

```
/stores/{storeId}/products/{productId}/options/{optionId}/stock
```

"어떤 스토어의, 어떤 상품의, 어떤 옵션의, 재고" 라는 소속 관계가 그대로 보인다.

### 4. 상태 없음(Stateless)

서버는 클라이언트의 상태를 저장하지 않는다. 모든 요청은 자체적으로 완결되어야 한다. 이 덕분에 서버를 자유롭게 스케일아웃할 수 있다.

---

## 원칙과 현실 사이 — 네 가지 의사결정

RESTful 원칙은 강력하지만, 실제 도메인에 부딪히면 **"순수한 RESTful"과 "팀이 읽기 쉬운 API" 사이의 트레이드오프**가 반드시 발생한다. 다음 네 가지는 설계 단계에서 가장 길게 논의했고, 각 결정에 명확한 근거를 남겨둔 사례다.

### 의사결정 1. 로그인은 자원인가? — `POST /users/login` 채택

```
POST /users/login
POST /users/logout
```

엄밀한 RESTful 관점에서 `login`, `logout`은 동사이므로 자원으로 표현하는 것이 정석이다.

```
POST /sessions          # 세션 생성 = 로그인
DELETE /sessions/{id}   # 세션 삭제 = 로그아웃
```

그럼에도 `/users/login`을 채택했다. 판단 근거는 세 가지다.

1. **자원 모델과 구현의 불일치** — JWT 기반 인증이라 서버에 "세션"이라는 영속 자원이 존재하지 않는다. `/sessions`는 실제 모델보다 추상화된 표현이고, 추상화가 실체를 가리는 순간 RESTful의 장점인 "URL이 곧 모델"이 무너진다.
2. **DELETE /sessions/{id}의 식별자 문제** — 클라이언트가 자기 세션 id를 들고 다녀야 하는데, JWT는 토큰 자체가 식별자라 `{id}`가 중복된다.
3. **컨벤션의 보편성** — `/users/login`은 업계 표준에 가까운 표현이라 신규 합류자의 학습 비용이 0에 수렴한다.

이 결정은 **RESTful을 목적이 아니라 도구로 쓴다**는 우리 팀의 설계 철학을 명문화한 첫 사례가 되었다.

### 의사결정 2. "내 가게" 조회 — `/stores/my` vs `/stores/{storeId}`

판매자가 자기 가게를 조회하는 API에서는 두 후보가 충돌했다.

```
후보 A: GET /stores/{storeId}   # 정석 — 자원 식별자가 명시적
후보 B: GET /stores/my           # 인증 컨텍스트로 식별자 대체
```

후보 A의 문제는 **보안 표면(attack surface)** 이었다. 클라이언트가 자기 storeId를 들고 다닌다는 것은 곧 다른 storeId를 넣어 호출할 수 있다는 뜻이다. 서버에서 "토큰의 주체 == 요청의 storeId"를 매번 검증해야 하고, 이 검증이 누락되면 곧바로 IDOR(Insecure Direct Object Reference) 취약점이 된다.

후보 B는 **인증 토큰을 식별자의 단일 출처(SSOT)** 로 삼는다. 클라이언트가 storeId를 알 필요가 없고, 권한 검증이 라우팅 단계에서 자연스럽게 일어난다. GitHub의 `/user`, Twitter의 `/account/verify_credentials` 등 주요 플랫폼이 이 패턴을 채택한 데도 같은 이유가 있다.

후보 B가 RESTful 자원 식별 원칙에서는 약간 비껴가지만, **보안 모델과 컨벤션의 명확성**이 그 비용을 상회한다고 판단했다.

### 의사결정 3. 상태 전이를 어떻게 표현할까 — PATCH 서브리소스 채택

어드민의 스토어 승인/거절/정지처럼 **상태 전이(state transition)를 어떻게 URL로 표현할 것인가**는 RESTful 설계의 단골 논쟁거리다. 세 가지 후보를 비교했다.

```
후보 A: PATCH /admin/stores/{storeId}            body: {"status": "APPROVED"}
후보 B: PATCH /admin/stores/{storeId}/approve
후보 C: POST  /admin/stores/{storeId}/approvals
```

각 후보의 트레이드오프를 정리하면 다음과 같다.

| 기준 | A (status 필드) | B (서브리소스) | C (이벤트 자원화) |
|------|------|------|------|
| RESTful 정합성 | ★★★ | ★★ | ★★★ |
| URL 자체 표현력 | ★ | ★★★ | ★★ |
| 권한 분기 용이성 | ★ | ★★★ | ★★ |
| 비즈니스 로직 분리 | ★ | ★★★ | ★★ |

후보 A의 문제는 **암묵적 계약**이다. URL만 보면 어떤 status 값이 허용되는지 알 수 없고, "APPROVED → REJECTED 전이가 가능한가?" 같은 비즈니스 규칙이 모두 body 검증 로직에 숨는다. 또한 승인/거절/정지가 **각자 다른 권한과 다른 후속 처리**(예: 승인 시 알림 발송, 정지 시 진행 중 주문 취소)를 가지는데, 이를 하나의 핸들러에서 분기하면 책임이 비대해진다.

후보 B를 택했다.

```java
@PatchMapping("/admin/stores/{storeId}/approve")
@PatchMapping("/admin/stores/{storeId}/reject")
@PatchMapping("/admin/stores/{storeId}/suspend")
```

- URL이 **허용 가능한 상태 전이의 명세** 역할을 한다
- 각 핸들러가 단일 책임이라 비즈니스 로직, 권한 체크, 이벤트 발행이 깔끔히 분리된다
- 승인/거절/정지를 각각 다른 RBAC 정책으로 묶기 쉽다

이 결정은 **헥사고날 + 이벤트 기반 설계**와도 자연스럽게 맞물린다. 각 엔드포인트가 하나의 도메인 이벤트(`StoreApproved`, `StoreRejected`, `StoreSuspended`)와 1:1로 대응되기 때문이다.

### 의사결정 4. 외부 시스템 콜백의 명명 — 표준 따르기

토스페이먼츠 결제 콜백 URL은 우리가 호출하는 API가 아니라 **외부 시스템이 우리 서버로 호출하는 진입점**이다.

```
GET /payments/success
GET /payments/fail
```

`success`, `fail`이 동사적 색채를 가지지만, 이 경우 의미를 결정하는 주체는 우리가 아니라 토스페이먼츠 SDK다. 토스 문서가 `successUrl`, `failUrl`로 명명한 이상, 자체 컨벤션을 강제하면 **외부 통합 문서와 우리 코드 사이의 인지적 단절**이 생긴다.

원칙: **외부 시스템과의 계약 지점에서는 외부 표준을 따른다.** RESTful 일관성은 우리가 통제 가능한 영역에서만 의미가 있다.

---

## 결과 — 우리 프로젝트의 URL 표

원칙을 정리하고 다시 짠 결과다.

### 사용자/스토어/관리자

```
POST   /users/sign-up                    # 일반 사용자 가입
POST   /users/sign-up/store              # 판매자 가입
POST   /users/login
POST   /users/logout
GET    /users/oauth/{provider}           # 소셜 로그인 시작
GET    /users/oauth/{provider}/callback  # 소셜 로그인 콜백

GET    /stores/my                        # 내 가게 조회

GET    /admin/stores                     # 어드민: 가게 목록
GET    /admin/stores/{storeId}
PATCH  /admin/stores/{storeId}/approve
PATCH  /admin/stores/{storeId}/reject
PATCH  /admin/stores/{storeId}/suspend
```

### 상품

```
GET    /products                                          # 전체 상품 검색
GET    /products/{productId}
GET    /stores/{storeId}/products                         # 특정 스토어의 상품
POST   /stores/{storeId}/products
PATCH  /stores/{storeId}/products/{productId}/options/{optionId}/stock
```

마지막 줄이 계층 구조의 절정이다. "어떤 스토어의, 어떤 상품의, 어떤 옵션의, 재고를 수정한다"가 URL에 그대로 표현됐다.

### 주문/배송

```
POST   /orders                                       # 주문 생성
GET    /orders                                       # 내 주문 목록
GET    /orders/{orderId}
PATCH  /stores/{storeId}/orders/{orderId}/delivery   # 판매자: 배송 상태 변경
```

소비자 시점의 주문은 `/orders`, 판매자 시점은 `/stores/{storeId}/orders`로 컨텍스트를 분리했다. 같은 데이터지만 **권한과 보여줄 정보가 다르기 때문**이다.

### 쿠폰

```
POST   /stores/{storeId}/coupon-events             # 판매자: 쿠폰 이벤트 생성
POST   /admin/coupon-events                        # 어드민: 글로벌 쿠폰 이벤트
PATCH  /admin/coupon-events/{couponEventId}
PATCH  /admin/coupon-events/{couponEventId}/deactivate

GET    /coupon-events                              # 사용자: 발급 가능한 쿠폰
GET    /coupon-events/my                           # 사용자: 내 쿠폰함
POST   /coupon-events/{couponEventId}/issue        # 선착순 쿠폰 발급
```

`/admin/...`, `/stores/{storeId}/...`, `/coupon-events/...` 세 가지 접두사로 **호출 주체가 누구인지**를 URL 첫 단어에서 드러낸다.

### 결제

```
GET    /payments/success
GET    /payments/fail
POST   /payments/{paymentId}/cancel
```

---

## RESTful의 장점

### 1. 학습 비용이 낮다

처음 합류한 개발자가 컨트롤러 목록만 봐도 80%는 짐작한다. `GET /products`가 뭘 하는지 설명할 필요가 없다.

### 2. 캐싱이 쉽다

GET 요청이 안전(safe)하다는 약속 덕에 CDN, 브라우저 캐시, Redis 캐시를 자연스럽게 끼워 넣을 수 있다. 우리 상품 조회도 Redis로 캐싱했고, [상품 조회 75배 개선](상품조회-성능개선-여정.md)의 토대다.

### 3. 멱등성이 재시도를 안전하게 만든다

PUT, DELETE는 멱등이라 클라이언트가 네트워크 오류 시 그냥 재시도하면 된다. POST는 비멱등이라 중복 처리를 따로 막아야 한다 — 이 차이를 인지하면 [결제 confirmPayment의 멱등성 처리](blog-시리즈3-동시성제어.md) 같은 설계가 자연스럽게 따라온다.

### 4. URL이 자기 문서가 된다

```
PATCH /stores/{storeId}/products/{productId}/options/{optionId}/stock
```

이 한 줄이 거의 한 문장 짜리 명세다. 별도 문서 없이도 의도가 전달된다.

### 5. HTTP 인프라(미들웨어, 게이트웨이)와 잘 맞는다

API 게이트웨이에서 메서드 단위로 라우팅하거나, 모니터링 도구가 GET/POST를 구분해 통계를 내거나 — 이런 도구들이 다 RESTful 가정 위에서 동작한다.

---

## RESTful의 단점

### 1. 복잡한 동작은 자연스럽게 표현이 안 된다

"장바구니 상품 3개를 한 번에 주문 + 쿠폰 적용 + 포인트 사용 + 결제 시작" 같은 트랜잭션은 어떻게 자원으로 표현할까? 억지로 자원화하면 오히려 어색해진다.

이런 경우 GraphQL이나 RPC 스타일이 더 잘 맞는다. RESTful이 만능은 아니다.

### 2. 한 화면당 N번 호출

상품 상세 페이지가 상품 정보 + 옵션 + 리뷰 + 판매자 정보가 필요하면 4번을 호출해야 한다. 이를 한 번에 가져오려면 별도 aggregation 엔드포인트를 만들거나 BFF(Backend For Frontend) 패턴을 도입해야 한다.

### 3. URL 설계에 숙련도가 필요하다

위에서 봤듯, 로그인을 `/sessions`로 할지 `/users/login`으로 할지, 승인을 `PATCH /stores/{id}`로 할지 `/stores/{id}/approve`로 할지 — **답이 하나가 아니다**. 팀 컨벤션이 없으면 매번 갈린다.

### 4. 버전 관리

자원 자체가 진화하면 어떻게 할지 — `/v1/products` vs `Accept-Version` 헤더 vs `?version=1` — 이것도 정답이 없다. 우리는 아직 v1만 있어서 미래에 풀 숙제다.

### 5. 학술적 RESTful은 사실상 불가능

Fielding이 말한 HATEOAS(서버 응답에 다음 가능한 액션 링크를 포함) 단계까지 가는 API는 현실에 거의 없다. 보통 우리가 RESTful이라고 부르는 건 **Richardson Maturity Model의 Level 2** 정도다. 이걸 인정하고 시작하는 게 현실적이다.

---

## 정리 — RESTful은 "지키는 것"이 아니라 "쓰는 것"

설계 단계에서 합의한 네 가지 판단 기준은 결국 다음 한 줄로 수렴했다.

> **API는 외부 계약이고, 외부 계약에서 가장 비싼 비용은 "해석의 모호함"이다.**

RESTful의 본질적 가치는 그 모호함을 줄이는 데 있다. 그래서 새 컨트롤러를 만들 때 우리는 네 가지 질문을 순서대로 던진다.

1. **자원이 무엇인가** → URL의 명사
2. **어떤 의미의 액션인가** → HTTP 메서드 + 멱등성/안전성
3. **누가 호출하는가** → URL 접두사로 호출 주체 분리 (`/admin`, `/stores/{id}`, 일반)
4. **소속 관계가 있는가** → 계층 구조로 표현

이 네 질문이 80%의 URL을 결정한다. 나머지 20% — 로그인을 `/sessions`로 할지, 상태 전이를 PATCH 서브리소스로 표현할지 — 는 **원칙과 실용성의 트레이드오프를 의식적으로 결정해야 하는 영역**이다.

이 결정을 회피하지 않고 매번 명확한 근거를 남긴 것이 이번 프로젝트의 핵심 자산이라고 생각한다. RESTful은 따라야 할 교리가 아니라, **모호함을 줄이기 위한 사고 도구**다. 도구로 쓸 때 비로소 일관성과 가독성, 그리고 캐싱·멱등성·HTTP 인프라 호환이라는 부수적 이득이 따라온다.
