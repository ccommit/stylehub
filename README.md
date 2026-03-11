
 (!! 이 문서는 작성중인 문서입니다.)
 # [Stylehub](https://bwj1207.tistory.com/category/stylehub%20%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8) 
---


<img width="1000" height="310" alt="image" src="https://github.com/user-attachments/assets/3e2f0549-1b24-4288-90d6-12c8a4f6bb92" />


---
### 무신사 같은 패션 커머스 플랫폼  **StyleHub**

> 💡 **Guide:**</br>
> 본 README의 모든 **파란색 키워드**는 프로젝트관련 위키 or 개발 블로그로 연결됩니다.  
> `Ctrl + 마우스 좌클릭`을 활용해 새 탭에서 상세 내용을 확인하며 읽으시는 것을 추천합니다</br>
> 궁금하신 기술 키워드를 클릭하여 설계 근거와 고민의 흔적을 확인해 보세요!

---

### 서비스 화면 미리보기

 👉[프론트 기획서 바로보기](https://ccommit.github.io/stylehub/)`Ctrl + 마우스 좌클릭`
 
---


## 목차
1. [프로젝트 소개](#1-프로젝트-소개)
2. [기술 스택 및 선정 이유](#2.-기술-스택-및-선정-이유)
3. [해결하고자 한 핵심 기술 과제](#3-해결하고자-한-핵심-기술-과제)
4. [API 문서](#4-api-문서)
5. [테스트 전략](#5-테스트-전략)
6. [트러블슈팅](#6-트러블슈팅)
7. [협업 방식](#7-협업-방식)

--- 
<details><summary><b> 개발 철학 및 마음가짐</b></summary>
 
### 협업을 고려한 개발
```
 프로그램은 컴퓨터가 실행하기 위해서가 아니라 사람이 읽기 위해 작성되어야 한다.
  — Harold Abelson
```

> 좋은 개발자란 단순히 동작하는 코드를 작성하는 사람이 아니라 다른 사람이 읽었을 때 쉽게 이해할 수 있는 코드를 작성하는 사람이라고 생각합니다.</br>
> 코드를 작성할 때 항상 미래의 유지보수 담당자가 누가 될지를 생각하며 개발에 임하였습니다.</br>
> 코드의 의도가 명확하게 드러나도록 작성하고, 지속적인 리팩토링을 통해 가독성과 구조를 개선하려 노력하였습니다.</br>

---
```
빠르게 틀리고, 빠르게 개선하자
```

> 개발 과정에서 완벽한 설계를 처음부터 만드는 것은 어렵다고 판단하였습니다.</br>
> 대신 빠르게 구현하고, 실행해보고, 문제를 발견하고, 개선하는 과정을 반복하는 것이 더 효율적이라고 생각하였습니다.

---

 

</details>

---


## [1. 프로젝트 소개](https://github.com/ccommit/stylehub/wiki/%EA%B8%B0%ED%9A%8D%EC%84%9C)

“**동시에 10만 명이 몰려도, 데이터는 절대 틀리지 않는다.**”

 대용량 쿠폰 발급과 주문/결제 동시성 문제를 해결하기 위해 설계한 트래픽 대응형 커머스 백엔드 프로젝트입니다.
 
> 대용량 트래픽 환경에서 발생하는 동시성 문제와 데이터 정합성 이슈를 직접 설계로 해결하고,
> 테스트 코드 기반 검증과 CI/CD 자동화를 통해 실제 운영 환경을 가정한 시스템 안정성을 구현했습니다.
 


 > 이 프로젝트를 시작하게 된  자세한 배경 → 👉[주제 선정 이유](https://bwj1207.tistory.com/116) `Ctrl + 마우스 좌클릭`

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

</details>


<details>
<summary><b>🏗️ 프로젝트 아키텍처</b></summary>
ㅁㄴㅇㄻㄴㅇㄻㄴㅇㄻ
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

 
## 2. 기술 스택 및 선정 이유 

 
| 분류 | 기술 스택 | 기술 선택 이유 |
|------|-----------|----------------|
| Language | Java 17| LTS 버전으로 안정성이 높고, 최신 기능 활용 가능 |
| Framework | Spring Boot 3.5.6 | 생태계 확장성 우수, 의존성 관리 최적화 |
| Database | [MySQL (InnoDB)](https://bwj1207.tistory.com/116) | 트랜잭션 ACID 보장, 인덱스 최적화로 안정적 데이터 처리 |
| ORM / Query | [Spring Data JPA + QueryDSL](https://bwj1207.tistory.com/116) | 객체 중심 설계, 타입 안정성 확보, 동적 쿼리 지원 |
| Caching | [Redis](https://bwj1207.tistory.com/116) | 분산 락 기반 동시성 제어, 빠른 캐싱 처리 |
| Infrastructure | [AWS EC2 / S3](https://bwj1207.tistory.com/116) | 클라우드 기반 확장성, 안정적 파일 스토리지 제공 |


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

## 7.가장 어려웠던 이슈

---

## 8. 협업 방식
- [1. 컨벤션(issue, pull request, code convention)](https://github.com/ccommit/stylehub/wiki/convention)  
- [2. 브렌치 관리 전략](https://github.com/ccommit/stylehub/wiki/%EB%B8%8C%EB%A0%8C%EC%B9%98-%EA%B4%80%EB%A6%AC-%EC%A0%84%EB%9E%B5)

--- 

 


