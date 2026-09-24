-- =====================================================================
-- 포인트 이력 커서 페이징 인덱스 운영 반영 DDL (MySQL 8)
--
-- 왜 필요한가
--   운영은 spring.jpa.hibernate.ddl-auto=validate 라 PointHistory 엔티티의 @Table(indexes) 선언이
--   운영 DB 에 인덱스를 만들지 않는다. 엔티티 선언은 테스트(H2)와 코드 문서화용이고, 운영에는 이 파일로 반영한다.
--
-- 대상 쿼리
--   point_histories : PointHistoryQueryRepository.findMyHistoriesWithCursor (GET /api/v1/users/me/points)
--                     WHERE user_id = ? [AND point_id < ?] ORDER BY point_id DESC LIMIT n
--
-- 실행 전 확인 필요 (확인하지 못한 사항)
--   운영 DB 에 같은 목적의 인덱스가 이미 있는지 확인하지 못했다.
--   point_histories.user_id 에 외래키 제약이 있으면 MySQL 이 user_id 인덱스를 자동으로 만든다.
--   InnoDB 보조 인덱스는 PK 를 뒤에 포함하므로 그 인덱스가 (user_id, point_id) 와 같은 역할을 할 수 있다.
--   아래 조회로 먼저 확인하고, 같은 컬럼 순서의 인덱스가 이미 있으면 CREATE 문은 실행하지 않는다.
--
--     SHOW INDEX FROM point_histories;
--
--   반영 후에는 EXPLAIN 으로 대상 쿼리가 새 인덱스를 쓰는지 확인한다(이 저장소에서 운영 EXPLAIN 결과는 확인하지 못했다).
--
-- 실행 방식
--   ALGORITHM=INPLACE, LOCK=NONE 으로 인덱스 생성 중에도 읽기·쓰기를 막지 않도록 요청한다.
--   서버가 이 조건으로 수행할 수 없으면 MySQL 이 에러를 내고 실행하지 않으므로, 그 경우 트래픽이 적은 시간에 다시 계획한다.
-- =====================================================================

-- 내 포인트 이력 커서 페이징
CREATE INDEX idx_point_histories_user_point_id
    ON point_histories (user_id, point_id)
    ALGORITHM = INPLACE LOCK = NONE;
