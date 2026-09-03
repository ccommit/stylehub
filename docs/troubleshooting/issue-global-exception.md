# Feature

## 🧾 이슈 제목
- [FEATURE] 글로벌 예외 처리 구현

---

## 이슈 목적
- 현재 모든 예외가 `IllegalArgumentException`으로 처리되어 HTTP 상태 코드와 에러 코드를 구분할 수 없음
- 커스텀 예외 + `GlobalExceptionHandler`를 도입하여 일관된 에러 응답 형식을 제공
- 프론트엔드가 에러 코드 기반으로 분기 처리할 수 있는 구조 확보
- StyleHub 서비스 고유 예외 계층을 구축하여, Java 표준 예외(`IllegalArgumentException` 등)와 비즈니스 예외를 명확히 분리하고, 예외 발생 시 어떤 도메인에서 어떤 이유로 실패했는지 즉시 파악할 수 있도록 한다

---

## 작업 항목 (Tasks)
- [ ] `BusinessException` 추상 클래스 생성 (HttpStatus, errorCode 포함)
- [ ] 도메인별 커스텀 예외 생성
  - [ ] `DuplicateEmailException` (409)
  - [ ] `DuplicateNicknameException` (409)
  - [ ] `UserNotFoundException` (404)
  - [ ] `InvalidPasswordException` (401)
  - [ ] `OAuthEmailConflictException` (409)
  - [ ] `InvalidOAuthCodeException` (400)
- [ ] `ErrorResponse` 통일 응답 DTO 생성
- [ ] `GlobalExceptionHandler` (`@RestControllerAdvice`) 구현
  - [ ] `BusinessException` 핸들러
  - [ ] `MethodArgumentNotValidException` 핸들러 (Bean Validation 실패)
  - [ ] `DataIntegrityViolationException` 핸들러 (DB unique 제약조건 위반)
  - [ ] 최상위 `Exception` 핸들러 (예상치 못한 에러 500)
- [ ] 기존 코드의 `IllegalArgumentException`을 커스텀 예외로 교체
  - [ ] `UserValidator`
  - [ ] `UserService`
  - [ ] `OAuthService`
- [ ] 기존 TODO 주석 제거

---

## 작업 상세 설명

- **ErrorResponse 통일 형식**:
  ```json
  {이슉
      "status": 409,
      "code": "DUPLICATE_EMAIL",
      "message": "이미 사용 중인 이메일입니다"
  }
  ```

- **BusinessException 구조**:
  - `BusinessException` (추상) → `status`, `code` 필드 보유
  - `DuplicateEmailException` → 409 CONFLICT, `DUPLICATE_EMAIL`
  - `DuplicateNicknameException` → 409 CONFLICT, `DUPLICATE_NICKNAME`
  - `UserNotFoundException` → 404 NOT_FOUND, `USER_NOT_FOUND`
  - `InvalidPasswordException` → 401 UNAUTHORIZED, `INVALID_PASSWORD`
  - `OAuthEmailConflictException` → 409 CONFLICT, `OAUTH_EMAIL_CONFLICT`
  - `InvalidOAuthCodeException` → 400 BAD_REQUEST, `INVALID_OAUTH_CODE`

- **GlobalExceptionHandler 처리 범위**:
  - `BusinessException` → 예외에 정의된 status + code 반환
  - `MethodArgumentNotValidException` → 400 + `VALIDATION_ERROR` + 필드별 메시지
  - `DataIntegrityViolationException` → 409 + `DUPLICATE_RESOURCE` (race condition 방어)
  - `Exception` → 500 + `INTERNAL_ERROR` + "서버 내부 오류가 발생했습니다"

- **교체 대상 코드**:
  - `UserValidator`: `IllegalArgumentException` → `DuplicateEmailException`, `DuplicateNicknameException`
  - `UserService.login()`: `IllegalArgumentException` → `UserNotFoundException`, `InvalidPasswordException`
  - `OAuthService`: `IllegalArgumentException` → `OAuthEmailConflictException`

- **서비스 고유 예외를 도입하여 해결하는 문제들**:

  - **문제 1: 예외 원인 식별 불가**
    현재 `IllegalArgumentException` 하나로 이메일 중복, 비밀번호 불일치, 유저 미존재를 모두 처리한다.
    로그에 `IllegalArgumentException`만 남으면 어떤 상황에서 발생한 에러인지 추적이 어렵다.
    → 고유 예외 도입 시 `DuplicateEmailException`, `InvalidPasswordException` 등 **예외 이름만으로 원인 파악** 가능

  - **문제 2: HTTP 상태 코드 매핑 불가**
    `IllegalArgumentException`은 Spring 기본 동작으로 500 에러를 반환한다.
    이메일 중복은 409, 유저 미존재는 404, 비밀번호 불일치는 401이 적절하지만 현재는 구분할 수 없다.
    → 고유 예외에 `HttpStatus`를 내장하여 `GlobalExceptionHandler`에서 **자동으로 적절한 상태 코드 반환**

  - **문제 3: 프론트엔드 에러 분기 불가**
    프론트엔드가 에러 응답을 받아도 메시지 문자열로만 구분해야 한다. 메시지가 변경되면 프론트 코드도 깨진다.
    → 고유 예외에 에러 코드(`DUPLICATE_EMAIL`, `USER_NOT_FOUND` 등)를 포함하여 **코드 기반 안정적인 분기 처리** 가능

  - **문제 4: 도메인 확장 시 예외 충돌**
    주문, 결제, 상품 등 도메인이 추가되면 모두 `IllegalArgumentException`을 던지게 되어 어떤 도메인에서 발생한 에러인지 구분 불가.
    → `BusinessException`을 상속하는 도메인별 예외 계층으로 **도메인 간 예외 격리** 확보
    ```
    BusinessException (추상)
    ├─ User 도메인
    │   ├─ DuplicateEmailException
    │   ├─ UserNotFoundException
    │   └─ InvalidPasswordException
    ├─ Order 도메인 (추후)
    │   ├─ OrderNotFoundException
    │   └─ InsufficientStockException
    └─ Payment 도메인 (추후)
        ├─ PaymentFailedException
        └─ RefundNotAllowedException
    ```

---
