# [Stylehub](https://bwj1207.tistory.com/category/stylehub%20%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8) 
> 💡 **Tip:**</br>
> 본 README의 모든 **파란색 키워드**는 프로젝트관련 위키 or 개발 블로그로 연결됩니다.  
> `Ctrl + 마우스 좌클릭`을 활용해 새 탭에서 상세 내용을 확인하며 읽으시는 것을 추천합니다</br>
> 궁금하신 기술 키워드를 클릭하여 설계 근거와 고민의 흔적을 확인해 보세요!
---
## 목차
1. [프로젝트 소개](#1-프로젝트-소개)
2. [기술 스택](#2-기술-스택)
3. [해결하고자 한 핵심 기술 과제](#3-해결하고자-한-핵심-기술-과제)
4. [ERD](#4-erd)
5. [API 문서](#5-api-문서)
6. [테스트 전략](#6-테스트-전략)
7. [트러블슈팅](#7-트러블슈팅)
8. [협업 방식](#8-협업-방식)

## [1. 프로젝트 소개](https://github.com/ccommit/stylehub/wiki/%EA%B8%B0%ED%9A%8D%EC%84%9C)

“**동시에 10만 명이 몰려도, 데이터는 절대 틀리지 않는다.**”
 대용량 쿠폰 발급과 주문/결제 동시성 문제를 해결하기 위해 설계한 트래픽 대응형 커머스 백엔드 프로젝트입니다.
 
 데이터 정합성 보장, 동시성 제어 전략, 테스트 코드 기반 검증, CI/CD 자동화를 통해 실제 운영 환경을 가정한 시스템 안정성을 구현했습니다.
 > 이 프로젝트를 시작하게 된  자세한 배경 → [주제 선정 이유](https://bwj1207.tistory.com/116)
---
## 2. 기술 스택
Java 17
Spring Boot
MySQL (InnoDB)
JPA + QueryDSL
Redis (동시성 제어 및 캐시)Redis는 대량 쿠폰 발급 시 선점 제어를 위해 사용
AWS EC2 / S3

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



