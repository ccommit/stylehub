-- =====================================================================
-- 재고·포인트·쿠폰 발급 한도 CHECK 제약 운영 반영 DDL (MySQL 8.0.16 이상)
--
-- 왜 필요한가
--   재고·포인트 차감과 쿠폰 발급 수 증가는 조건부 UPDATE(WHERE stock_quantity >= ? 등)가 음수·초과를 막는다.
--   하지만 그 경로를 거치지 않는 변경(수동 SQL, 이후 추가될 코드)은 막지 못해, 마지막 방어선을 DB 에 둔다.
--   엔티티의 @Table(check = ...) 선언은 테스트 DB 에만 제약을 만들고, 운영(ddl-auto=validate)에는 이 파일로 반영한다.
--
-- 수동 적용 (apply-on-deploy.txt 에 넣지 않는다)
--   CHECK 추가는 테이블을 다시 쓰는(COPY) DDL 이라 그동안 쓰기가 막힌다. 트래픽이 적은 시간에 실행한다.
--   기존 데이터가 조건을 어기면 추가 자체가 실패하므로, 먼저 아래 조회가 모두 0 인지 확인한다.
--
--     SELECT COUNT(*) FROM products_options WHERE stock_quantity < 0;
--     SELECT COUNT(*) FROM users WHERE point_balance < 0;
--     SELECT COUNT(*) FROM coupon_events WHERE issued_count > issue_count;
--
-- 재실행 안전
--   MySQL 은 ADD CONSTRAINT IF NOT EXISTS 를 지원하지 않아, 같은 이름의 제약이 없을 때만 추가한다.
-- =====================================================================

SET @exists := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_products_options_stock_nonnegative');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE products_options ADD CONSTRAINT chk_products_options_stock_nonnegative CHECK (stock_quantity >= 0)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_users_point_nonnegative');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE users ADD CONSTRAINT chk_users_point_nonnegative CHECK (point_balance >= 0)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
                WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_coupon_events_issued_within_limit');
SET @ddl := IF(@exists = 0,
    'ALTER TABLE coupon_events ADD CONSTRAINT chk_coupon_events_issued_within_limit CHECK (issued_count <= issue_count)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
