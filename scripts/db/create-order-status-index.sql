-- =====================================================================
-- 결제 대기 주문 보정 조회 인덱스 운영 반영 DDL (MySQL 8)
--
-- 왜 필요한가
--   OrderTimeoutScheduler.compensateOrphanedOrders 가 Redis 타이머가 누락된 결제 대기 주문을 찾는다.
--   인덱스가 없으면 orders 전체를 훑으므로, 주문이 쌓일수록 보정 한 번의 비용이 커진다.
--   운영은 ddl-auto=validate 라 Order 엔티티의 @Table(indexes) 선언이 인덱스를 만들지 않아 이 파일로 반영한다.
--
-- 대상 쿼리
--   orders : OrderRepository.findExpiredOrderIds
--            WHERE order_status = 'PENDING' AND created_at < ? AND order_id > ? ORDER BY order_id LIMIT 100
--   결제 대기 주문은 최근 10분 안의 소수라, (order_status, order_id) 로 범위를 좁힌 뒤 created_at 을 거르는 편이
--   (order_status, created_at) 로 찾은 뒤 order_id 로 다시 정렬하는 것보다 keyset 조건과 정렬에 그대로 맞는다.
--
-- 수동 적용
--   인덱스 생성은 운영 부하를 보고 시점을 고르도록 apply-on-deploy.txt 에 넣지 않았다.
--   SHOW INDEX FROM orders; 로 같은 컬럼 순서의 인덱스가 없는지 먼저 확인하고, 반영 뒤 EXPLAIN 으로 사용 여부를 확인한다.
--   ALGORITHM=INPLACE, LOCK=NONE 으로 요청해 생성 중에도 읽기·쓰기를 막지 않는다. 불가능하면 MySQL 이 실행하지 않는다.
-- =====================================================================

CREATE INDEX idx_orders_status_order_id
    ON orders (order_status, order_id)
    ALGORITHM = INPLACE LOCK = NONE;
