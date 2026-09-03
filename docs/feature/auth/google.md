# 구글 OAuth 로그인 테스트 가이드

## 사전 준비

### 1. Google Cloud Console 설정 확인

- [Google Cloud Console](https://console.cloud.google.com/) 접속
- OAuth 2.0 클라이언트 ID가 생성되어 있어야 한다
- **승인된 리다이렉트 URI**에 `http://localhost:8080/login/oauth2/code/google`이 등록되어 있어야 한다

### 2. application.properties 확인

```properties
google.client-id=발급받은-클라이언트-ID
google.client-secret=발급받은-클라이언트-시크릿
google.redirect-uri=http://localhost:8080/login/oauth2/code/google
```

### 3. MySQL users 테이블 확인

구글 로그인으로 생성되는 유저는 아래 컬럼이 사용된다:

| 컬럼 | 값 |
|---|---|
| name | 구글 프로필 이름 |
| email | 구글 이메일 |
| provider | GOOGLE |
| provider_user_id | 구글 고유 ID (sub) |
| password | NULL (OAuth 유저는 비밀번호 없음) |
| role | USER (기본값) |

---

## 테스트 순서

### Step 1. 서버 실행

```bash
./gradlew bootRun
```

서버가 `http://localhost:8080`에서 정상 구동되는지 확인한다.

### Step 2. 구글 인증 URL 받기

**포스트맨에서:**

```
GET http://localhost:8080/api/v1/oauth/google
```

**응답 예시:**

```json
{
    "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?client_id=554336...&redirect_uri=http://localhost:8080/login/oauth2/code/google&response_type=code&scope=email%20profile"
}
```

### Step 3. 브라우저에서 구글 로그인

1. 응답의 `authorizationUrl` 값을 **복사**한다
2. **브라우저 주소창**에 붙여넣고 Enter
3. 구글 로그인 화면이 나타난다
4. 구글 계정으로 로그인하고 **동의(허용)** 클릭

### Step 4. 결과 확인

구글 로그인 후 브라우저가 아래 URL로 리다이렉트된다:

```
http://localhost:8080/login/oauth2/code/google?code=4/0AQSTgQ...긴문자열
```

서버가 켜져 있으면 자동으로 처리되어 **브라우저에 JSON 응답이 바로 표시**된다.

**신규 유저 응답 (첫 로그인):**

```json
{
    "userId": 1,
    "name": "홍길동",
    "email": "user@gmail.com",
    "newUser": true
}
```

**기존 유저 응답 (재로그인):**

```json
{
    "userId": 1,
    "name": "홍길동",
    "email": "user@gmail.com",
    "newUser": false
}
```

---

## 포스트맨에서 콜백을 직접 테스트하고 싶은 경우

브라우저가 아닌 포스트맨에서 콜백 API를 직접 호출하려면 authorization code를 수동으로 추출해야 한다.

### 방법 1: 서버를 끈 상태에서 code 추출

1. **서버를 끈다** (`Ctrl+C`)
2. 브라우저에서 Step 2의 `authorizationUrl`로 접속 → 구글 로그인
3. 로그인 후 브라우저가 리다이렉트하지만, 서버가 꺼져있어 **에러 페이지**가 뜬다
4. 브라우저 주소창의 URL을 확인한다:

   ```
   http://localhost:8080/login/oauth2/code/google?code=4/0AQSTgQE...긴문자열
   ```

5. `code=` 뒤의 값을 **복사**한다
6. **서버를 다시 켠다** (`./gradlew bootRun`)
7. 포스트맨에서 호출:

   ```
   GET http://localhost:8080/login/oauth2/code/google?code=복사한값
   ```

### 방법 2: 브라우저 개발자 도구에서 code 추출

1. 서버를 **켠 상태**에서 브라우저 개발자 도구(F12) → **Network** 탭 열기
2. `authorizationUrl`로 접속 → 구글 로그인
3. Network 탭에서 `google?code=...` 요청을 찾는다
4. 해당 요청의 Query String에서 `code` 값을 복사
5. 이 code는 이미 사용되었으므로 **재사용 불가** (확인 용도로만 사용)

### 주의사항

| 항목 | 설명 |
|---|---|
| code는 **1회용** | 한 번 사용하면 즉시 만료된다. 같은 code로 두 번 요청하면 실패 |
| code는 **약 5~10분 내 만료** | 복사 후 빠르게 요청해야 한다 |
| code에 **URL 인코딩 문자 포함** 가능 | `%2F` 같은 문자가 있으면 그대로 복사해야 한다 |

---

## 에러 케이스 테스트

### 1. 잘못된 code로 요청

```
GET http://localhost:8080/login/oauth2/code/google?code=invalid_code
```

→ 500 에러 (Google 토큰 교환 실패)

### 2. 이미 일반 회원가입한 이메일로 구글 로그인

일반 회원가입(`POST /api/v1/users/sign-up`)으로 등록한 이메일과 동일한 구글 계정으로 로그인 시도 시:

→ `"이미 일반 회원가입으로 등록된 이메일입니다"` 에러 반환

### 3. 동일 구글 계정으로 재로그인

→ 정상 처리. `newUser: false`로 응답

---

## 전체 플로우 다이어그램

```
┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│  클라이언트 │     │  백엔드   │     │  Google  │     │    DB    │
└────┬─────┘     └────┬─────┘     └────┬─────┘     └────┬─────┘
     │                │                │                │
     │ GET /api/v1/   │                │                │
     │ oauth/google   │                │                │
     │───────────────►│                │                │
     │                │                │                │
     │ authorizationUrl                │                │
     │◄───────────────│                │                │
     │                │                │                │
     │ 브라우저에서 구글 로그인 페이지 접속                  │
     │───────────────────────────────►│                │
     │                │                │                │
     │          구글 로그인 + 동의      │                │
     │◄───────────────────────────────│                │
     │  (redirect: /login/oauth2/     │                │
     │   code/google?code=xxx)        │                │
     │                │                │                │
     │ GET /login/oauth2/code/google  │                │
     │   ?code=xxx    │                │                │
     │───────────────►│                │                │
     │                │                │                │
     │                │  code → token  │                │
     │                │───────────────►│                │
     │                │  access_token  │                │
     │                │◄───────────────│                │
     │                │                │                │
     │                │  유저 정보 요청  │                │
     │                │───────────────►│                │
     │                │  email, name   │                │
     │                │◄───────────────│                │
     │                │                │                │
     │                │  findByEmail   │                │
     │                │───────────────────────────────►│
     │                │  유저 조회/생성  │                │
     │                │◄───────────────────────────────│
     │                │                │                │
     │  OAuthLoginResponse             │                │
     │  (userId, name, email, newUser) │                │
     │◄───────────────│                │                │
     │                │                │                │
```
