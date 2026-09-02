# HTTP 세션 기반 인증 구현 - 단계별 프롬프트

## 1단계: 세션 상수 정의
세션에서 사용할 상수 클래스를 생성해줘.
- 패키지: ccommit.stylehub.common.constants
- SessionConstants 클래스 (final, private 생성자)
- SESSION_USER_ID, SESSION_USER_ROLE 등 세션 attribute key 상수 정의
- Javadoc 헤더 필수 (@author, @created, 목적 설명)
- 명시적 개별 import (와일드카드 import 금지)

## 2단계: 로그인 시 세션 생성
기존 UserController의 로그인/OAuth 엔드포인트에서 로그인 성공 시 HttpSession에 사용자 정보를 저장하도록 수정해줘.
- 세션 생성은 컨트롤러 레이어에서 처리 (서비스에 HttpServletRequest 넘기지 않기)
- UserService.login()은 기존대로 UserLoginResponse만 반환, 세션 처리는 컨트롤러에서
- 세션 고정 공격(Session Fixation) 방지: 로그인 성공 시 기존 세션 무효화 후 새 세션 생성 (request.getSession().invalidate() → request.getSession(true))
- session.setAttribute()로 userId, role 저장
- SessionConstants의 상수 사용
- 세션 생성은 반드시 트랜잭션 완료 후에 수행 (트랜잭션 실패 시 세션 생성 방지)
- 일반 로그인 + Google OAuth 두 경로 모두 동일하게 세션 생성 적용
- 기존 TransactionTemplate 패턴 유지
- 기존 LoginEvent 발행 로직 유지

## 3단계: 로그아웃 API 구현
로그아웃 API를 구현해줘.
- UserController에 `POST /api/v1/users/logout` 추가
- HttpSession.invalidate()로 세션 무효화
- ResponseEntity<Void> 반환, HttpStatus.OK
- Javadoc 헤더 필수

## 4단계: 인증 인터셉터 구현
인증이 필요한 API를 검증하는 인터셉터를 구현해줘.
- 패키지: ccommit.stylehub.common.config
- AuthInterceptor 구현 (HandlerInterceptor)
- preHandle()에서 세션 존재 여부 확인
- 세션 없으면 BusinessException 던지기 (UNAUTHORIZED 관련 ErrorCode 추가)
- Javadoc 헤더 필수

## 5단계: 역할 검증 인터셉터 구현
STORE, ADMIN 등 특정 역할만 접근 가능한 API를 검증하는 인터셉터를 구현해줘.
- RoleCheckInterceptor 구현 (HandlerInterceptor)
- 세션에서 역할 정보 확인
- 커스텀 어노테이션 @RequiredRole(UserRole.ADMIN) 방식으로 적용
- 권한 없으면 BusinessException 던지기 (FORBIDDEN 관련 ErrorCode 추가)
- Javadoc 헤더 필수

## 6단계: WebConfig에 인터셉터 등록
기존 WebConfig에 인터셉터를 등록해줘.
- AuthInterceptor: 인증이 필요한 경로에 적용
- RoleCheckInterceptor: 역할 검증이 필요한 경로에 적용
- 로그인, 회원가입, OAuth 콜백 경로는 반드시 인증 제외 (excludePathPatterns) — 빠뜨리면 로그인 자체가 불가능해짐
- 기존 OAuthProvider 컨버터 설정 유지

## 7단계: 세션 설정
application.properties에 세션 관련 설정을 추가해줘.
- 세션 타임아웃 설정 (예: 30분)
- 쿠키 설정 (HttpOnly, SameSite 등)

## 8단계: ErrorCode 추가
인증/인가 관련 ErrorCode를 추가해줘.
- UNAUTHORIZED: 로그인이 필요합니다 (401)
- FORBIDDEN: 접근 권한이 없습니다 (403)
- SESSION_EXPIRED: 세션이 만료되었습니다 (401)

## 9단계: 세션 사용자 정보 조회 유틸
컨트롤러에서 세션의 사용자 정보를 편리하게 조회할 수 있는 유틸을 만들어줘.
- SessionUtils 클래스 (static 메서드)
- getUserId(HttpServletRequest), getUserRole(HttpServletRequest)
- 세션 없으면 BusinessException 던지기
- Javadoc 헤더 필수

## 10단계: 테스트 코드
HTTP 세션 인증 관련 테스트를 작성해줘.
- 로그인 후 세션 생성 확인
- 로그아웃 후 세션 무효화 확인
- 미인증 요청 시 401 응답 확인
- 권한 없는 요청 시 403 응답 확인
- MockHttpSession 활용
- H2 인메모리 DB 사용
