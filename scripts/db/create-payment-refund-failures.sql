-- =====================================================================
-- 자동 환불 실패 기록 테이블 운영 반영 DDL (MySQL 8)
--
-- 왜 필요한가
--   만료·취소된 주문에 늦게 들어온 PG 승인은 자동 환불한다. 이 환불이 실패하면 결제는 이미 만료로 커밋돼
--   이후 대조로 다시 찾을 수 없어, 기록을 남겨 PaymentRefundRetryScheduler 가 같은 멱등 키로 다시 환불한다.
--   운영은 ddl-auto=validate 라 엔티티(PaymentRefundFailure)의 테이블을 만들지 않으므로 배포 전에 먼저 만든다.
--
-- 컬럼 타입
--   Hibernate 가 MySQL 에 만드는 타입과 맞췄다(@Enumerated(STRING) → enum, BaseEntity 시각 → datetime(6)).
--
-- 배포 파이프라인이 적용한다
--   scripts/db/apply-on-deploy.txt 에 올라가 있어 배포 때마다 다시 실행되므로 IF NOT EXISTS 로 둔다.
-- =====================================================================

CREATE TABLE IF NOT EXISTS payment_refund_failures (
    refund_failure_id BIGINT       NOT NULL AUTO_INCREMENT,
    payment_key       VARCHAR(200) NOT NULL,
    pg_order_id       VARCHAR(64)  NOT NULL,
    status            ENUM ('PENDING', 'RESOLVED') NOT NULL,
    attempt_count     INT          NOT NULL,
    last_error        VARCHAR(500) NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NULL,
    PRIMARY KEY (refund_failure_id),
    UNIQUE KEY uk_payment_refund_failures_payment_key (payment_key),
    KEY idx_payment_refund_failures_status (status, refund_failure_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
