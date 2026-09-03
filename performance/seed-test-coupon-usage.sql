-- ============================================
-- 시나리오 2-2 (쿠폰 사용 주문) 부하 테스트용 시드
--
-- 생성:
--   - 쿠폰 이벤트 4 종 (FIXED / RATE / MIN_ORDER / EXPIRED)
--   - 모든 perf_buyer 에게 4 종 쿠폰 사전 발급 (1000 buyer × 4 = 4000 UserCoupon)
--
-- 사용:
--   mysql -u root -p stylehub < performance/seed-test-coupon-usage.sql
--
-- 매 측정 *시작 전* 에 실행 — issued_count + user_coupons 모두 RESET 후 재발급.
-- ============================================

-- 1. 기존 PERF_USE_* 쿠폰 + UserCoupon 정리 (멱등)
DELETE uc FROM user_coupons uc
  JOIN coupon_events ce ON ce.coupon_event_id = uc.coupon_event_id
  WHERE ce.name LIKE 'PERF\\_USE\\_%';

DELETE FROM coupon_events WHERE name LIKE 'PERF\\_USE\\_%';

-- 2. STORE user_id 결정
SET @perf_store := (SELECT user_id FROM users WHERE role = 'STORE' ORDER BY user_id LIMIT 1);

-- 3. 4 종 쿠폰 이벤트 생성
INSERT INTO coupon_events
    (store_user_id, name, coupon_type, discount_type, discount_value, min_order_amount,
     issue_count, issued_count, started_at, expired_at, is_active, created_at)
VALUES
    -- FIXED 정액 3000 원, 최소 주문 0 (제한 없음) — 쿠폰 100% 적용 부하 측정용
    (@perf_store, 'PERF_USE_FIXED', 'STORE', 'FIXED', 3000, 0,
     2000, 0, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 1, NOW()),

    -- RATE 정률 10%, 최소 주문 0 — 정률 할인 검증
    (@perf_store, 'PERF_USE_RATE', 'STORE', 'RATE', 10, 0,
     2000, 0, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 1, NOW()),

    -- 최소 주문 20만원 (제품 가격 평균 10만이라 *대부분 미달* — MIN_ORDER 검증용)
    (@perf_store, 'PERF_USE_MIN', 'STORE', 'FIXED', 5000, 200000,
     2000, 0, DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_ADD(NOW(), INTERVAL 7 DAY), 1, NOW()),

    -- 만료된 쿠폰 — 만료 검증용
    (@perf_store, 'PERF_USE_EXPIRED', 'STORE', 'FIXED', 5000, 0,
     2000, 0, DATE_SUB(NOW(), INTERVAL 7 DAY), DATE_SUB(NOW(), INTERVAL 1 HOUR), 1, NOW());

-- 4. 모든 perf_buyer 에게 4 종 쿠폰 사전 발급 (CROSS JOIN, UNIQUE 제약 무관)
INSERT INTO user_coupons (user_id, coupon_event_id, status, used_at)
SELECT u.user_id, ce.coupon_event_id, 'UNUSED', NULL
FROM users u
CROSS JOIN coupon_events ce
WHERE u.email LIKE 'perf_buyer_%@perf.test'
  AND ce.name LIKE 'PERF\\_USE\\_%';

-- 5. coupon_events.issued_count 동기화 (실제 발급된 수만큼)
UPDATE coupon_events ce
SET issued_count = (
    SELECT COUNT(*) FROM user_coupons uc WHERE uc.coupon_event_id = ce.coupon_event_id
)
WHERE ce.name LIKE 'PERF\\_USE\\_%';

-- 6. 검증
SELECT ce.coupon_event_id, ce.name, ce.discount_type, ce.discount_value,
       ce.min_order_amount, ce.issued_count,
       CASE WHEN ce.expired_at < NOW() THEN 'EXPIRED' ELSE 'ACTIVE' END AS status
FROM coupon_events ce
WHERE ce.name LIKE 'PERF\\_USE\\_%'
ORDER BY ce.coupon_event_id;

SELECT COUNT(*) AS total_user_coupons,
       SUM(CASE WHEN status = 'UNUSED' THEN 1 ELSE 0 END) AS unused,
       SUM(CASE WHEN status = 'USED' THEN 1 ELSE 0 END) AS used
FROM user_coupons uc
JOIN coupon_events ce ON ce.coupon_event_id = uc.coupon_event_id
WHERE ce.name LIKE 'PERF\\_USE\\_%';
-- 기대: total = 4000, unused = 4000, used = 0
