-- =====================================================================
-- 주문 배송 완료 시각 컬럼 운영 반영 DDL (MySQL 8)
--
-- 왜 필요한가
--   배송 완료 뒤 7일 환불 기한을 orders.updated_at 으로 세면, 배송 완료 뒤 주문 행이 다른 이유로 수정될 때마다 기한이 늘어난다.
--   Order.deliveredAt 을 추가해 배송 완료로 바뀌는 순간을 따로 기록한다.
--   운영은 ddl-auto=validate 라 컬럼이 없으면 기동이 실패하므로 배포 전에 먼저 추가한다.
--
-- 재실행 안전
--   MySQL 8 은 ADD COLUMN IF NOT EXISTS 를 지원하지 않아, information_schema 로 컬럼 유무를 확인한 뒤에만 추가한다.
--   끝에 NULL 컬럼을 추가하는 것이라 MySQL 8.0.29 이상에서는 테이블을 다시 쓰지 않고(INSTANT) 바로 끝난다.
--
-- 백필
--   이미 배송 완료된 주문은 배송 완료 시각 기록이 없어 마지막 수정 시각으로 채운다. 기존 동작과 같은 기준이다.
--   애플리케이션도 값이 비어 있으면 updated_at 으로 대신한다(Order.refundPeriodStartedAt).
--
-- 배포 파이프라인이 적용한다
--   scripts/db/apply-on-deploy.txt 에 올라가 있어 배포 때마다 다시 실행된다.
-- =====================================================================

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'delivered_at'
);
SET @ddl := IF(@column_exists = 0, 'ALTER TABLE orders ADD COLUMN delivered_at DATETIME(6) NULL', 'DO 0');
PREPARE add_delivered_at FROM @ddl;
EXECUTE add_delivered_at;
DEALLOCATE PREPARE add_delivered_at;

UPDATE orders SET delivered_at = updated_at
WHERE order_status = 'DELIVERED' AND delivered_at IS NULL;
