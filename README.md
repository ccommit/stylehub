
 (!! 이 문서는 작성중인 문서입니다.)
 # [Stylehub](https://bwj1207.tistory.com/category/stylehub%20%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8) 
---


<img width="1000" height="310" alt="image" src="https://github.com/user-attachments/assets/3e2f0549-1b24-4288-90d6-12c8a4f6bb92" />


---
### 무신사 같은 패션 커머스 플랫폼  **StyleHub**

> 💡 **Guide:**</br>
> 본 README의 모든 **파란색 키워드**는 프로젝트관련 위키 혹은 개발 블로그로 연결됩니다.  
> `Ctrl + 마우스 좌클릭`을 활용해 새 탭에서 상세 내용을 확인하며 읽으시는 것을 추천합니다</br>

---

### 📌 서비스 화면 프로토 타입
**"구체적인 화면 설계를 통해 백엔드 로직의 완성도를 높였습니다."**

 👉[프론트 기획서 바로보기](https://ccommit.github.io/stylehub/)`Ctrl + 마우스 좌클릭` </br>
 
> 본 프로젝트는 백엔드 중심으로 진행되었으나, 실제 서비스의 흐름을 시각적으로 정의하기 위해 화면 프로토타입을 제작했습니다. </br>
> 머릿속으로 구상한 비즈니스 로직이 실제 화면에서 구현될 때 발생할 수 있는 논리적 모순을 사전에 방지하고자 했습니다. </br>
> 화면의 흐름을 따라가며 예외 상황을 미리 시뮬레이션했고, 이를 통해 요구사항에 최적화된 견고한 백엔드 아키텍처를 구축했습니다.
 
---
<details><summary><b> 개발 철학 및 마음가짐</b></summary>

### 1. 협업의 시작은 '문서화'로부터
> **"기억은 기록을 이길 수 없고, 기록은 삭제를 이길 수 없다. 하지만 기록된 지식은 팀의 문화를 만든다."**

* 1인 프로젝트였지만 언제든 동료가 합류할 수 있는 확장성을 염두에 두었습니다.
* 기술적 의사 결정,구현 로직, 시퀀스 다이어그램, 트러블슈팅 과정을 체계적으로 문서화하여, 별도의 온보딩 없이도 즉시 기여할 수 있는 고효율 개발 환경을 구축하였습니다.

---

### 2. 읽기 쉬운 코드가 최고의 협업 도구
> **" 컴퓨터가 이해할 수 있는 코드는 어느 바보나 다 짤 수 있다. 좋은 프로그래머는 사람이 이해할 수 있는 코드를 짠다. "**

* **동료를 위한 배려:** 코드는 쓰는 시간보다 읽히는 시간이 더 길기에 항상 '미래의 담당자'를 배려하며 개발하였습니다..
* **의도가 드러나는 설계:** 명확한 네이밍과 지속적인 리팩토링을 통해 별도의 설명 없이 코드 자체로 의도가 전달되는 'Self-describing Code'를 작성하려고 끊임없이 고민하였습니다.

---

### 3. '동작'을 넘어 '검증'에 집중한 완결성
> **"테스트는 버그가 없음을 증명하는 것이 아니라, 버그가 있음을 찾아내는 과정이다."**

  * **철저한 단위 테스트(Unit Test) 수행:** 로직의 가장 작은 단위부터 견고하게 구축하기 위해 핵심 비즈니스 로직에 대한 단위 테스트를 수행했습니다. 이는 코드 변경 시 발생할 수 있는 사이드 이펙트를 방지하고, 리팩토링의 심리적 안정감을 확보하는 기반이 되었습니다.
* **실패 케이스 중심의 검증:** 단순히 기능이 작동하는 '성공 케이스(Happy Path)'에만 만족하지 않았습니다. 의도적으로 잘못된 입력값이나 예외 상황(Edge Case)을 설정한 **실패 테스트를 병행**하여, 어떤 극한의 상황에서도 시스템이 안전하게 대응할 수 있도록 설계했습니다.
* **추적 가능한 구조와 완결성:** 테스트를 통해 발견된 취약점을 즉각 보완하며 코드의 기술적 완결성을 높였습니다. 검증 가능한 구조를 유지함으로써 문제 발생 시 원인을 빠르게 파악할 수 있는 환경을 조성했습니다.

</details>

---

## 📌 목차
1. [프로젝트 소개](#1-프로젝트-소개)
2. [기술 스택](#2-기술-스택)
3. [해결하고자 한 핵심 기술 과제](#3-해결하고자-한-핵심-기술-과제)
4. [API 문서](#4-api-문서)
5. [테스트 전략](#5-테스트-전략)
6. [트러블슈팅](#6-트러블슈팅)
7. [가장 어려웠던 이슈](#7-가장-어려웠던-이슈)
8. [시퀀스 다이어그램](#8-시퀀스-다이어그램)
9. [협업 방식](#9-협업-방식)


---


## [1. 프로젝트 소개](https://github.com/ccommit/stylehub/wiki/%EA%B8%B0%ED%9A%8D%EC%84%9C)

“**동시에 10만 명이 몰려도, 데이터는 절대 틀리지 않는다.**”

 대용량 쿠폰 발급과 주문/결제 동시성 문제를 해결하기 위해 설계한 트래픽 대응형 커머스 백엔드 프로젝트입니다.
 
> 대용량 트래픽 환경에서 발생하는 동시성 문제와 데이터 정합성 이슈를 직접 설계로 해결하고,
> 테스트 코드 기반 검증과 CI/CD 자동화를 통해 실제 운영 환경을 가정한 시스템 안정성을 구현했습니다.
 
* 🎯 **주제 선정 이유** : [프로젝트 배경 및 계기 확인](https://bwj1207.tistory.com/116) `Ctrl + 마우스 좌클릭`</br>
* 📝 **상세 기능 명세** : [기획서 바로가기](https://github.com/ccommit/stylehub/wiki/%EA%B8%B0%ED%9A%8D%EC%84%9C) `Ctrl + 마우스 좌클릭`</br>
---

<details>
<summary><b> 주요 기능 </b></summary>

- 일반 회웝가입과 구글 로그인을 할수 있다.
  
- 매장 관리자가 상품을 등록 수정 삭제할수 있다.
  
- 상품 카테고리는 2차 카테고리까지 있으며, admin이 등록한다.
- 상품을 등록하기 위해서는 매니저 회원가입 기능이 구현되어야 한다.
- 일반 사용자는  상품을 검색하고 상세정보를 확인할수 있다.
- 일반 사용자는 토스페이먼츠로 결제 할수 있다.
- 이용자가 쿠폰·포인트를 적용하여  할인을  받을 수 있다.
- 결제는 PG사(토스페이먼츠) 연동으로 처리하며 "금액 위변조 방지와 멱등성을 보장 및 결제이력관리"를 구현한다.
- 장바구니는 클라이언트에서 관리하며 "주문 시 상품 목록을 서버로 전달하는 방식"으로 구현한다
- 쿠폰은 Admin 플랫폼 전체 발행 | 매장 관리자(MANAGER)는  본인 매장별로 발행할 수 있다.
- 쿠폰의 종류는 정액/정율 타입이 있고 발행자는 발행 쿠폰을 취소 할 수 있다.

---

</details> 


<details>
<summary><b>📂 프로젝트 폴더 구조</b></summary>

```
StyleHub 프로젝트의 패키지 구조는 다음과 같은 목표를 가지고 설계되었습니다.

- 도메인 중심 설계를 통해 비즈니스 로직의 응집도 향상

- 도메인 간 의존성을 최소화하여 유지보수성 개선

- 새로운 기능이 추가되더라도 기존 구조를 크게 변경하지 않는 확장 가능한 구조

이러한 구조를 통해 프로젝트 규모가 커지더라도 도메인 단위로 기능을 이해하고 관리할 수 있도록 설계했습니다.
```
---
```
src
└── main
    └── java
        └── com.stylehub
            ├── domain
            │   ├── user
            │   │   ├── controller
            │   │   ├── service
            │   │   ├── repository
            │   │   ├── entity
            │   │   └── dto
            │   ├── store
            │   │   ├── controller
            │   │   ├── service
            │   │   ├── repository
            │   │   ├── entity
            │   │   └── dto
            │   ├── product
            │   │   ├── controller
            │   │   ├── service
            │   │   ├── repository
            │   │   ├── entity
            │   │   └── dto
            │   ├── order
            │   │   ├── controller
            │   │   ├── service
            │   │   ├── repository
            │   │   ├── entity
            │   │   └── dto
            │   ├── payment
            │   │   ├── controller
            │   │   ├── service
            │   │   ├── repository
            │   │   ├── entity
            │   │   └── dto
            │   ├── coupon
            │   │   ├── controller
            │   │   ├── service
            │   │   ├── repository
            │   │   ├── entity
            │   │   └── dto
            │   └── point
            │       ├── controller
            │       ├── service
            │       ├── repository
            │       ├── entity
            │       └── dto
            └── global
                ├── config
                ├── exception
                ├── response
                └── security
```
---
</details>


<details>
<summary><b>🏗️ 프로젝트 아키텍처</b></summary>
ㅁㄴㅇㄻㄴㅇㄻㄴㅇㄻ


 ---
</details> 

 
<details>
<summary><b>🗄️ ERD </b></summary>
 
```StyleHub는 패션 이커머스 서비스의 핵심 도메인인 사용자, 상품, 주문, 결제, 쿠폰을 중심으로 데이터 모델을 설계했습니다.

주요 설계 기준은 다음과 같습니다.


- 도메인 중심 데이터 모델링을 통해 각 비즈니스 개념을 명확한 엔티티로 분리

- 주문, 결제, 쿠폰 사용 과정에서 데이터 정합성을 유지할 수 있도록 관계 설계

- 이커머스 서비스 특성을 고려하여 확장 가능한 구조로 모델링

### 주요 도메인 관계

- User → Order (1)
  하나의 사용자는 여러 개의 주문을 생성할 수 있습니다.

- Order → OrderItem (1)
  하나의 주문에는 여러 개의 상품이 포함될 수 있습니다.

- Order → Payment (1:1)
  하나의 주문은 하나의 결제 정보를 가집니다.

- User → Coupon (1)
  하나의 사용자는 여러 개의 쿠폰을 보유할 수 있습니다.

- Store → Product (1)
  하나의 스토어는 여러 개의 상품을 판매할 수 있습니다.
```
</details>

---

 
## 2. 기술 스택

 
| 분류 | 기술 스택 | 기술 선택 이유 |
|------|-----------|----------------|
| Language | Java 17| LTS 버전으로 안정성이 높고, 최신 기능 활용 가능 |
| Framework | Spring Boot 3.5.6 | 생태계 확장성 우수, 의존성 관리 최적화 |
| Database | [MySQL (InnoDB)](https://bwj1207.tistory.com/116) | 트랜잭션 ACID 보장, 인덱스 최적화로 안정적 데이터 처리 |
| ORM / Query | [Spring Data JPA + QueryDSL](https://bwj1207.tistory.com/116) | 객체 중심 설계, 타입 안정성 확보, 동적 쿼리 지원 |
| Caching | [Redis](https://bwj1207.tistory.com/116) | 분산 락 기반 동시성 제어, 빠른 캐싱 처리 |
| Infrastructure | [AWS EC2](https://bwj1207.tistory.com/116) | 클라우드 기반 확장성, 안정적 파일 스토리지 제공 |


---


## 3. 해결하고자 한 핵심 기술 과제
   - 대용량 쿠폰 발급 동시성 제어
   - 재고 선점 경쟁 해결 전략
   - 주문/결제 트랜잭션 정합성 보장

---

 

## 4. API 문서

---

## 5. 테스트 전략

---

## 6. 트러블슈팅

---

## 7. 가장 어려웠던 이슈

---

## 8. 시퀀스 다이어그램
<details>
<summary><b>주문 단건 처리 시퀀스</b></summary>

 ``` mermaid

sequenceDiagram
    actor Client as Client
    participant Controller as OrderController
    participant Service as OrderService
    participant Repository as OrderRepository (JPA)
    participant DB as DB

    Client->>Controller: POST /api/v1/orders

    Controller->>Service: 주문 생성 요청

    Service->>Repository: 상품 옵션 조회
    Repository->>DB: SELECT products_options
    DB-->>Repository: ProductOption 엔티티 목록

    alt 재고 부족
        Repository-->>Service: 재고 부족
        Service-->>Controller: OutOfStockException
        Controller-->>Client: 409 재고 부족
    end

    Repository-->>Service: 상품 옵션 조회 성공

    Service->>Repository: 쿠폰 / 포인트 조회
    Repository->>DB: SELECT user_coupons, users
    DB-->>Repository: 쿠폰 / 포인트 정보 (없으면 0)

    Service->>Service: 최종 결제금액 계산<br/>(상품가 - 쿠폰할인 - 등급할인 - 포인트)

    Service->>Repository: 재고 차감 (비관적 락)
    Repository->>DB: SELECT ... FOR UPDATE → UPDATE stock
    DB-->>Repository: 재고 차감 완료

    Service->>Repository: 주문 저장
    Repository->>DB: INSERT orders status=PENDING
    DB-->>Repository: 저장 완료

    Repository-->>Service: 주문 저장 완료
    Service-->>Controller: 주문 생성 완료
    Controller-->>Client: 201 Created {orderId, finalAmount}

    Note over Client: 결제하기 버튼 클릭
    Note over Client: tossOrderId, finalAmount로<br/>토스 결제 위젯 호출


 ```
</details> 

<details>
<summary><b>토스 결제 시퀀스</b></summary>

``` mermaid
   sequenceDiagram
    actor Client as 클라이언트
    participant CT as PaymentController
    participant SV as PaymentService
    participant Repo as PaymentRepository (JPA)
    participant DB as DB

    Note over Client,DB: ① 결제 준비

    Client->>CT: POST /api/payments/prepare
    Note over CT: @Valid 요청값 검증<br/>(orderName, amount, customerEmail)
    CT->>SV: preparePayment(request)
    Note over SV: orderId 생성 / status = READY
    SV->>Repo: 결제 정보 저장
    Repo->>DB: INSERT payments
    Note over DB: 커넥션 타임아웃: 5s<br/>쿼리 타임아웃: 5s
    alt DB 타임아웃 발생
        DB-->>Repo: DataAccessException
        Repo-->>SV: DB_TIMEOUT
        SV-->>CT: CustomException (DB_TIMEOUT)
        CT-->>Client: 503 Service Unavailable
    end
    DB-->>Repo: 저장 완료
    Repo-->>SV: 결제 저장 완료
    SV-->>CT: 결제 준비 완료
    CT-->>Client: 200 OK (orderId, amount)

    Note over Client,DB: ② 결제 승인

    Client->>CT: POST /api/payments/confirm
    Note over CT: @Valid 요청값 검증<br/>(paymentKey, orderId, amount)
    CT->>SV: confirmPayment(request)
    SV->>Repo: 결제 정보 조회
    Repo->>DB: SELECT payments WHERE orderId
    Note over DB: 커넥션 타임아웃: 5s<br/>쿼리 타임아웃: 5s
    alt DB 타임아웃 발생
        DB-->>Repo: DataAccessException
        Repo-->>SV: DB_TIMEOUT
        SV-->>CT: CustomException (DB_TIMEOUT)
        CT-->>Client: 503 Service Unavailable
    end
    DB-->>Repo: 조회 완료
    Repo-->>SV: Payment(READY)
    Note over SV: 금액 검증 (amount 비교)
    alt 주문 없음 / 금액 불일치
        SV-->>CT: CustomException
        CT-->>Client: 400 / 404
    end
    alt 결제 인증 후 10분 초과
        SV-->>CT: CustomException (PAYMENT_EXPIRED)
        CT-->>Client: 400 Bad Request
    end
    Note over SV: POST /v1/payments/confirm<br/>Authorization: Basic {secretKey:Base64}
    SV->>Repo: 결제 상태 업데이트
    Repo->>DB: UPDATE payments SET paymentKey, status=DONE
    Note over DB: 커넥션 타임아웃: 5s<br/>쿼리 타임아웃: 5s
    alt DB 타임아웃 발생
        DB-->>Repo: DataAccessException
        Repo-->>SV: DB_TIMEOUT
        SV-->>CT: CustomException (DB_TIMEOUT)
        CT-->>Client: 503 Service Unavailable
    end
    DB-->>Repo: 저장 완료
    Repo-->>SV: 결제 상태 업데이트 완료
    SV-->>CT: 결제 승인 완료
    CT-->>Client: 200 OK (paymentKey, orderId, status, amount, approvedAt)
```
 
</details> 

<details>
<summary><b>쿠폰 발행 시퀀스</b></summary>

``` mermaid

sequenceDiagram
    actor Client as Client (STORE or Admin)
    participant Controller as CouponController
    participant Service as CouponService
    participant Repository as CouponRepository (JPA)
    participant DB as DB

    Client->>Controller: POST /api/v1/coupons<br/>CouponCreateRequest(discountType, quantity, startAt, endAt)
    activate Controller
    Note over Controller: @Valid 요청값 검증<br/>(할인타입, 발급수량, 시작일/종료일)
    Controller->>Service: createCoupon(role, storeId, CouponCreateRequest)
    activate Service
    Note over Service: @Transactional<br/>role=STORE: storeId 쿠폰 발행<br/>role=ADMIN: 플랫폼 전체 쿠폰 발행

    Service->>Repository: findByCondition(discountType, startAt, endAt)
    activate Repository
    Repository->>DB: SELECT FROM coupons
    activate DB
    DB-->>Repository: 조회 결과 반환
    deactivate DB
    Repository-->>Service: Optional(Coupon)
    deactivate Repository

    alt 중복 쿠폰 존재
        Service-->>Controller: 예외 전파 (중복 쿠폰)
        Controller-->>Client: 쿠폰 생성 비즈니스예외 발생
    else 중복 없음
        Service->>Repository: save(Coupon)
        activate Repository
        Repository->>DB: INSERT INTO coupons
        activate DB
        DB-->>Repository: 저장 완료
        Repository-->>Service: Coupon (저장된 엔티티)
        Service-->>Controller: CouponResponse(couponId, discountType, quantity, startAt, endAt)
        Controller-->>Client: 201 Created CouponResponse
    end

    deactivate DB
    deactivate Repository
    deactivate Service
    deactivate Controller
```
 
</details> 

---


## 9. 협업 방식
- [1. 컨벤션(issue, pull request, code convention)](https://github.com/ccommit/stylehub/wiki/convention)  
- [2. 브렌치 관리 전략](https://github.com/ccommit/stylehub/wiki/%EB%B8%8C%EB%A0%8C%EC%B9%98-%EA%B4%80%EB%A6%AC-%EC%A0%84%EB%9E%B5)

--- 

 


