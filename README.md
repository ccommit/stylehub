# [Stylehub](https://bwj1207.tistory.com/category/stylehub%20%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8) 
무신사 같은 패션 커머스 플랫폼 StyleHub
> 💡 **Guide:**</br>
> 본 README의 모든 **파란색 키워드**는 프로젝트관련 위키 or 개발 블로그로 연결됩니다.  
> `Ctrl + 마우스 좌클릭`을 활용해 새 탭에서 상세 내용을 확인하며 읽으시는 것을 추천합니다</br>
> 궁금하신 기술 키워드를 클릭하여 설계 근거와 고민의 흔적을 확인해 보세요!
> [프론트 기획서](https://github.com/user-attachments/files/25795434/index.html)
---
## 목차
1. [프로젝트 소개](#1-프로젝트-소개)
2. [Issue및 trouble shooting Posting](https://github.com/ccommit/stylehub/wiki/Issue%EB%B0%8F-trouble-shooting-Posting)
3. [기술 스택](#2-기술-스택)
4. [해결하고자 한 핵심 기술 과제](#3-해결하고자-한-핵심-기술-과제)
5. [ERD](#4-erd)
6. [API 문서](#5-api-문서)
7. [테스트 전략](#6-테스트-전략)
8. [트러블슈팅](#7-트러블슈팅)
9. [협업 방식](#8-협업-방식)

## [1. 프로젝트 소개](https://github.com/ccommit/stylehub/wiki/%EA%B8%B0%ED%9A%8D%EC%84%9C)

“**동시에 10만 명이 몰려도, 데이터는 절대 틀리지 않는다.**”

 대용량 쿠폰 발급과 주문/결제 동시성 문제를 해결하기 위해 설계한 트래픽 대응형 커머스 백엔드 프로젝트입니다.
 
> 대용량 트래픽 환경에서 발생하는 동시성 문제와 데이터 정합성 이슈를 직접 설계로 해결하고,
> 테스트 코드 기반 검증과 CI/CD 자동화를 통해 실제 운영 환경을 가정한 시스템 안정성을 구현했습니다.
 


 > 이 프로젝트를 시작하게 된  자세한 배경 → 👉[주제 선정 이유](https://bwj1207.tistory.com/116) `Ctrl + 마우스 좌클릭`
---
## 🛠 기술 스택 및 선정 이유
> 각 기술 스택을 클릭하면 해당 기술을 도입하며 고민했던 상세 블로그 포스팅으로 이동합니다.


| 분류 | 기술 스택 | 기술 선택 이유 |
|------|-----------|----------------|
| Language | [Java 17](https://bwj1207.tistory.com/116) | LTS 버전으로 안정성이 높고, 최신 기능 활용 가능 |
| Framework | [Spring Boot 3.5.6](https://bwj1207.tistory.com/116) | 생태계 확장성 우수, 의존성 관리 최적화 |
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

## 4. ERD 
<details>
  <summary> ERD</summary>
</details>

---

## 5. API 문서

---

## 6. 테스트 전략

---

## 7. 트러블슈팅

---
## 8. 협업 방식
- [1. 컨벤션(issue, pull request, code convention)](https://github.com/ccommit/stylehub/wiki/convention)  
- [2. 브렌치 관리 전략](https://github.com/ccommit/stylehub/wiki/%EB%B8%8C%EB%A0%8C%EC%B9%98-%EA%B4%80%EB%A6%AC-%EC%A0%84%EB%9E%B5)
- 



