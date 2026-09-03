-- ============================================
-- 시나리오 2 (주문 → 결제) 부하 테스트용 buyer 시드
--
-- 생성:
--   - users 500명: email perf_buyer_1@perf.test ~ perf_buyer_500@perf.test
--   - addresses 500건: 각 buyer 당 1개 (default)
--
-- 비밀번호: Test1234!
--   BCrypt cost 10 해시. Java BCrypt 라이브러리(at.favre.lib.crypto.bcrypt)는
--   $2a, $2b, $2y prefix 모두 지원하므로 호환된다.
--
-- 사용:
--   mysql -u root -p stylehub < performance/seed-test-buyers.sql
--
-- 멱등성:
--   기존 perf_buyer_*@perf.test 행을 먼저 삭제 후 재생성하므로 반복 실행 안전.
--   addresses 는 FK(user_id) 로 연결되어 user 삭제 시 함께 정리되도록
--   먼저 명시적으로 삭제한다.
-- ============================================

-- 1. 기존 perf_buyer 데이터 정리 (멱등성)
DELETE FROM addresses
WHERE user_id IN (
    SELECT user_id FROM users WHERE email LIKE 'perf_buyer_%@perf.test'
);
DELETE FROM users WHERE email LIKE 'perf_buyer_%@perf.test';

-- 2. buyer 500명 생성
--    10x10x5 sequence 로 1..500 생성, 동일 BCrypt 해시 사용 (single hash 빠름)
INSERT INTO users (
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
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4) c
) nums
WHERE seq <= 500;

-- 3. 각 buyer 당 address 1건 생성 (default 배송지)
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
WHERE u.email LIKE 'perf_buyer_%@perf.test';

-- 4. 검증 쿼리 (선택 — 결과만 확인)
SELECT
    (SELECT COUNT(*) FROM users WHERE email LIKE 'perf_buyer_%@perf.test') AS buyer_count,
    (SELECT COUNT(*) FROM addresses WHERE user_id IN
        (SELECT user_id FROM users WHERE email LIKE 'perf_buyer_%@perf.test')) AS address_count;
-- 기대: buyer_count = 500, address_count = 500
