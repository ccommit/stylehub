-- =====================================================================
-- Idempotency-Key 처리 기록 테이블 운영 반영 DDL (MySQL 8)
--
-- 왜 필요한가
--   운영은 spring.jpa.hibernate.ddl-auto=validate 라 IdempotencyRecord 엔티티의 테이블을 만들지 않는다.
--   이 테이블이 없으면 validate 단계에서 애플리케이션이 뜨지 않으므로, 이 기능을 배포하기 전에 먼저 실행한다.
--
-- 컬럼 타입
--   Hibernate 가 MySQL 에 만드는 타입과 맞췄다(로컬 MySQL 8 에서 ddl-auto 로 만든 orders 테이블 기준:
--   @Enumerated(STRING) → enum(...) 알파벳순, BaseEntity 시각 → datetime(6), String → varchar).
--   타입이 다르면 validate 가 실패한다. 반영 후 애플리케이션 기동 로그에서 스키마 검증 통과를 확인한다.
--
-- 유니크 키
--   (user_id, operation, idempotency_key) 유니크 인덱스가 같은 키의 동시 요청을 한 건만 통과시킨다.
--   이름은 IdempotencyGuard 가 키 충돌인지 판별하는 데 쓰므로 엔티티의 UNIQUE_KEY_NAME 과 같아야 한다.
--
-- 외래 키를 두지 않는 이유
--   user_id 에 FK 를 걸면 InnoDB 가 INSERT 마다 users 행에 공유 락을 건다. 사용자 존재는 세션 인증이 보장하므로 값만 저장한다.
--
-- 배포 파이프라인이 적용한다
--   scripts/db/apply-on-deploy.txt 에 올라가 있어, 배포 때 jar 를 교체하기 전에 실행된다.
--   매 배포마다 다시 실행되므로 IF NOT EXISTS 로 두 번째 실행부터는 아무 일도 하지 않게 한다.
-- =====================================================================

CREATE TABLE IF NOT EXISTS idempotency_records (
    idempotency_record_id BIGINT       NOT NULL AUTO_INCREMENT,
    user_id               BIGINT       NOT NULL,
    operation             ENUM ('ORDER_CREATE', 'PAYMENT_CANCEL') NOT NULL,
    idempotency_key       VARCHAR(64)  NOT NULL,
    request_hash          VARCHAR(64)  NOT NULL,
    resource_id           BIGINT       NULL,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NULL,
    PRIMARY KEY (idempotency_record_id),
    UNIQUE KEY uk_idempotency_records_user_operation_key (user_id, operation, idempotency_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
