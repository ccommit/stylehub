-- ============================================================
-- 시나리오 4 (선착순 쿠폰 발급) 부하 테스트용 시드
--
-- 사용법:
--   mysql -u root -p stylehub < performance/seed-test-coupons.sql
--
-- 매 측정 사이클 *시작 전* 에 실행하면 깨끗한 상태로 RESET 됨.
-- (기존 PERF_* 쿠폰 이벤트 + UserCoupon 모두 삭제 후 새로 생성)
--
-- 생성되는 이벤트 4 종:
--   PERF_C_100      — issue_count=100, 정합성 검증 (500 명 부하 → 정확히 100 발급)
--   PERF_C_1000     — issue_count=1000, 한계 TPS 측정 (1000 명 부하 → 전원 성공)
--   PERF_C_EXPIRED  — expired_at 과거, 전원 400 (CP004) 검증
--   PERF_C_FUTURE   — started_at 미래, 전원 400 (CP003) 검증
-- ============================================================

-- 1) 기존 테스트 쿠폰 + 발급된 UserCoupon 모두 정리 (idempotent RESET)
DELETE uc FROM user_coupons uc
  JOIN coupon_events ce ON ce.coupon_event_id = uc.coupon_event_id
  WHERE ce.name LIKE 'PERF\\_%';

DELETE FROM coupon_events WHERE name LIKE 'PERF\\_%';

-- 2) 사용할 STORE user_id 결정 (가장 작은 STORE user_id)
SET @perf_store_user := (SELECT user_id FROM users WHERE role = 'STORE' ORDER BY user_id LIMIT 1);

-- 3) 4 종 쿠폰 이벤트 생성
INSERT INTO coupon_events
    (store_user_id, name, coupon_type, discount_type, discount_value, min_order_amount,
     issue_count, issued_count, started_at, expired_at, is_active, created_at)
VALUES
    -- 100 장: 정합성 검증용 (500 명 부하 → 정확히 100 발급, 400 SOLD_OUT)
    (@perf_store_user, 'PERF_C_100', 'STORE', 'FIXED', 3000, 0,
     100, 0,
     DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY),
     1, NOW()),

    -- 1000 장: 한계 TPS 측정용 (1000 명 부하 → 전원 성공, 경합만)
    (@perf_store_user, 'PERF_C_1000', 'STORE', 'FIXED', 5000, 0,
     1000, 0,
     DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY),
     1, NOW()),

    -- 만료된 쿠폰: 전원 400 (CP004) 검증
    (@perf_store_user, 'PERF_C_EXPIRED', 'STORE', 'FIXED', 1000, 0,
     50, 0,
     DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR),
     1, NOW()),

    -- 시작 전 쿠폰: 전원 400 (CP003) 검증
    (@perf_store_user, 'PERF_C_FUTURE', 'STORE', 'FIXED', 1000, 0,
     50, 0,
     DATE_ADD(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY),
     1, NOW());

-- 4) 결과 확인
SELECT coupon_event_id, name, issue_count, issued_count,
       started_at, expired_at, is_active
FROM coupon_events
WHERE name LIKE 'PERF\\_%'
ORDER BY coupon_event_id;

-- 5) 검증 쿼리 (측정 후 실행할 것 — 기록용)
-- ============================================================
-- -- 발급 정합성: issue_count == issued_count == COUNT(user_coupons) 인가
-- SELECT ce.coupon_event_id, ce.name, ce.issue_count, ce.issued_count,
--        (SELECT COUNT(*) FROM user_coupons uc WHERE uc.coupon_event_id = ce.coupon_event_id) AS uc_count
-- FROM coupon_events ce
-- WHERE ce.name LIKE 'PERF\\_%';
--
-- -- 중복 발급 검출 (있으면 안 됨)
-- SELECT user_id, coupon_event_id, COUNT(*) AS dup
-- FROM user_coupons
-- WHERE coupon_event_id IN (SELECT coupon_event_id FROM coupon_events WHERE name LIKE 'PERF\\_%')
-- GROUP BY user_id, coupon_event_id
-- HAVING COUNT(*) > 1;
-- ============================================================
