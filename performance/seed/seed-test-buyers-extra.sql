-- ============================================
-- 추가 buyer 501~1000 시드 (기존 1~500 보존)
--
-- seed-test-buyers.sql 의 DELETE 가 FK 충돌 (orders → addresses) 로 실패.
-- 기존 buyer 데이터는 보존하고 *없는 buyer 만 INSERT IGNORE* 로 추가.
--
-- 사용:
--   mysql -u root -p stylehub < performance/seed-test-buyers-extra.sql
-- ============================================

-- 1. buyer 501~1000 INSERT (email UNIQUE constraint 로 기존 skip)
INSERT IGNORE INTO users (
    name, email, password, role, grade,
    total_spent, point_balance, is_active,
    created_at, updated_at
)
SELECT
    CONCAT('perf_buyer_', seq),
    CONCAT('perf_buyer_', seq, '@perf.test'),
    '$2y$10$IaHd2MOHqgjP4HhXBijo4uJ00fPrcZLNX7hMTkUVJHhPygbJSYUpO',
    'USER', 'BRONZE',
    0, 0, true,
    NOW(), NOW()
FROM (
    SELECT a.N + b.N * 10 + c.N * 100 + 1 AS seq
    FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
          UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
) nums
WHERE seq BETWEEN 501 AND 1000;

-- 2. address 가 없는 perf_buyer 에게만 address 1건 추가
INSERT INTO addresses (
    user_id, label, recipient_name, phone,
    zip_code, street_address, detail_address,
    is_default, created_at, updated_at
)
SELECT
    u.user_id,
    '집',
    u.name,
    '010-0000-0000',
    '12345',
    '서울시 강남구 테헤란로 1',
    CONCAT('테스트동 ', SUBSTRING(u.email, 12), '호'),
    true,
    NOW(), NOW()
FROM users u
LEFT JOIN addresses a ON a.user_id = u.user_id
WHERE u.email LIKE 'perf_buyer_%@perf.test'
  AND a.address_id IS NULL;

-- 3. 검증
SELECT
    (SELECT COUNT(*) FROM users WHERE email LIKE 'perf_buyer_%@perf.test') AS buyer,
    (SELECT COUNT(DISTINCT a.user_id) FROM addresses a JOIN users u ON u.user_id = a.user_id
     WHERE u.email LIKE 'perf_buyer_%@perf.test') AS addr;
-- 기대: buyer = 1000, addr = 1000
