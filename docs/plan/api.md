# StyleHub API 노션 관리

API를 개발할 때마다 아래 정보를 참고하여 노션 StyleHub API 데이터베이스에 자동으로 추가/업데이트해줘.

## 노션 데이터베이스 정보

- Database URL: https://www.notion.so/81d83e7693bc4e3aaa2bd3cf277cb48e
- Data Source ID: f61cbf1c-044f-4360-8123-31c97aeed55b

## 스키마

| 컬럼 | 타입 | 설명 |
|------|------|------|
| CRUD | Title | API 이름 (예: 회원가입, 상품 등록) |
| HTTP | Select | GET(파랑), POST(초록), PUT(노랑), PATCH(주황), DELETE(빨강) |
| URI | Text | 엔드포인트 (예: /api/v1/users/sign-up) |
| 상태 | Status | 시작 전, 진행 중, 완료 |
| 태그 | Multi Select | user, oauth, store, product, order, payment, delivery, likes, coupon, point, address, mypage |

## 페이지 content 형식

각 API 페이지 안에 아래 형식으로 Request Body, Response Body를 작성한다.

### 테이블
```
<table header-row="true">
<tr color="purple_bg">
<td>논리 이름</td>
<td>이름</td>
<td>타입</td>
</tr>
<tr>
<td>{논리 이름}</td>
<td>{필드명}</td>
<td>{타입}</td>
</tr>
</table>
```

### JSON 코드블록
테이블 아래에 예시 JSON을 코드블록으로 작성한다.

## 규칙

1. API 구현이 완료되면 상태를 "완료"로 변경한다
2. 구현 중이면 상태를 "진행 중"으로 변경한다
3. 새 API를 추가할 때는 해당 도메인 태그를 반드시 포함한다
4. 마이페이지 관련 API는 도메인 태그 + "mypage" 태그를 함께 건다
5. 새 태그가 필요하면 Data Source를 업데이트하여 태그 옵션을 먼저 추가한다
