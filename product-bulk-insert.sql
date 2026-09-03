-- ============================================
-- StyleHub 테스트 데이터 생성
-- 스토어 100개 × 12카테고리 × 850개 = 1,020,000 상품
-- ============================================

-- 1. 스토어 유저 100명 생성
INSERT INTO users (name, email, password, role, grade, total_spent, point_balance, is_active, created_at, updated_at)
SELECT
    CONCAT('stylehub', seq),
    CONCAT('stylehub', seq, '@stylehub.com'),
    '$2a$10$dXJ3SW6G7P50lGmMQgel6uBP0p4CY9ORMmHhGJgMBN0mqFVqXqJO6',
    'STORE', 'BRONZE', 0, 0, true, NOW(), NOW()
FROM (
    SELECT a.N + b.N * 10 + 1 AS seq
    FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
) nums
WHERE seq <= 100;

-- 2. 스토어 100개 생성 (APPROVED)
INSERT INTO stores (user_id, name, description, like_count, status, approved_at, created_at, updated_at)
SELECT
    u.user_id,
    CONCAT(
        ELT(1 + FLOOR(RAND() * 20),
            '트렌드샵', '패션플러스', '하이라이트', '스타일온',
            '옷장고', '루미에르', '어반룩', '데일리룩',
            '시크로드', '모드니', '클로젯', '비바패션',
            '라움샵', '에디샵', '코드나인', '블랙라벨',
            '화이트룸', '그레이존', '인디고샵', '소울드레서'
        ),
        ' ', SUBSTRING(MD5(RAND()), 1, 3)
    ),
    '트렌디한 패션 아이템을 판매합니다',
    FLOOR(RAND() * 500), 'APPROVED', NOW(), NOW(), NOW()
FROM users u
WHERE u.email LIKE 'stylehub%@stylehub.com'
ORDER BY u.user_id;

-- 3. 상품 생성 (각 스토어 × 12카테고리 × 850개 = 1,020,000건)
INSERT INTO products (store_id, name, main_category, sub_category, description, price, image_url, like_count, created_at, updated_at)
SELECT
    s.store_id,
    CONCAT(
        ELT(1 + FLOOR(RAND() * 5),
            CASE sub.sub_cat
                WHEN 'SNEAKERS' THEN '나이키 에어'
                WHEN 'DRESS_SHOES' THEN '페라가모'
                WHEN 'RUNNING_SHOES' THEN '아식스 젤'
                WHEN 'JACKET' THEN '노스페이스'
                WHEN 'SWEATSHIRT' THEN '스투시 맨투맨'
                WHEN 'T_SHIRT' THEN '유니클로 티'
                WHEN 'DENIM_PANTS' THEN '리바이스'
                WHEN 'SKIRT' THEN '자라 스커트'
                WHEN 'SHORT_PANTS' THEN '나이키 숏츠'
                WHEN 'NECKLACE' THEN '티파니 목걸이'
                WHEN 'RING' THEN '불가리 링'
                WHEN 'GLASSES' THEN '레이밴'
            END,
            CASE sub.sub_cat
                WHEN 'SNEAKERS' THEN '뉴발란스'
                WHEN 'DRESS_SHOES' THEN '구찌 로퍼'
                WHEN 'RUNNING_SHOES' THEN '호카 본디'
                WHEN 'JACKET' THEN '무신사 자켓'
                WHEN 'SWEATSHIRT' THEN '챔피온 후드'
                WHEN 'T_SHIRT' THEN '커버낫 티'
                WHEN 'DENIM_PANTS' THEN '디스이즈네버'
                WHEN 'SKIRT' THEN 'H&M 스커트'
                WHEN 'SHORT_PANTS' THEN '아디다스 숏'
                WHEN 'NECKLACE' THEN '스와로브스키'
                WHEN 'RING' THEN '판도라 링'
                WHEN 'GLASSES' THEN '오클리 선글'
            END,
            CASE sub.sub_cat
                WHEN 'SNEAKERS' THEN '아디다스 삼바'
                WHEN 'DRESS_SHOES' THEN '닥터마틴'
                WHEN 'RUNNING_SHOES' THEN '나이키 줌'
                WHEN 'JACKET' THEN '파타고니아'
                WHEN 'SWEATSHIRT' THEN '나이키 맨투맨'
                WHEN 'T_SHIRT' THEN '폴로 반팔'
                WHEN 'DENIM_PANTS' THEN '캘빈클라인'
                WHEN 'SKIRT' THEN '미쏘 롱스커트'
                WHEN 'SHORT_PANTS' THEN '무신사 숏팬'
                WHEN 'NECKLACE' THEN '골든듀 체인'
                WHEN 'RING' THEN '제이에스티나'
                WHEN 'GLASSES' THEN '젠틀몬스터'
            END,
            CASE sub.sub_cat
                WHEN 'SNEAKERS' THEN '컨버스 척'
                WHEN 'DRESS_SHOES' THEN '탠디 구두'
                WHEN 'RUNNING_SHOES' THEN '브룩스 글리'
                WHEN 'JACKET' THEN '아크테릭스'
                WHEN 'SWEATSHIRT' THEN '아디다스 맨투'
                WHEN 'T_SHIRT' THEN '무인양품 티'
                WHEN 'DENIM_PANTS' THEN '무신사 데님'
                WHEN 'SKIRT' THEN '에잇세컨즈'
                WHEN 'SHORT_PANTS' THEN '챔피온 숏츠'
                WHEN 'NECKLACE' THEN '미니골드'
                WHEN 'RING' THEN '스톤헨지'
                WHEN 'GLASSES' THEN '뮤지크 안경'
            END,
            CASE sub.sub_cat
                WHEN 'SNEAKERS' THEN '반스 올드스쿨'
                WHEN 'DRESS_SHOES' THEN '클락스 데저트'
                WHEN 'RUNNING_SHOES' THEN '미즈노 웨이브'
                WHEN 'JACKET' THEN '디스커버리'
                WHEN 'SWEATSHIRT' THEN '이즈 맨투맨'
                WHEN 'T_SHIRT' THEN '탑텐 반팔'
                WHEN 'DENIM_PANTS' THEN '에잇세컨즈'
                WHEN 'SKIRT' THEN '스파오 치마'
                WHEN 'SHORT_PANTS' THEN '유니클로 숏'
                WHEN 'NECKLACE' THEN '오에스티 펜던'
                WHEN 'RING' THEN '로이드 링'
                WHEN 'GLASSES' THEN '프로젝트프로'
            END
        ),
        ' ', SUBSTRING(MD5(RAND()), 1, 3)
    ),
    sub.main_cat,
    sub.sub_cat,
    CONCAT('테스트 상품입니다'),
    FLOOR(10000 + RAND() * 190000),
    CONCAT('https://example.com/img/', sub.sub_cat, '/', nums.seq, '.jpg'),
    FLOOR(RAND() * 1000),
    NOW(), NOW()
FROM stores s
JOIN (
    SELECT 'SHOES' AS main_cat, 'SNEAKERS' AS sub_cat UNION ALL
    SELECT 'SHOES', 'DRESS_SHOES' UNION ALL
    SELECT 'SHOES', 'RUNNING_SHOES' UNION ALL
    SELECT 'TOP', 'JACKET' UNION ALL
    SELECT 'TOP', 'SWEATSHIRT' UNION ALL
    SELECT 'TOP', 'T_SHIRT' UNION ALL
    SELECT 'BOTTOM', 'DENIM_PANTS' UNION ALL
    SELECT 'BOTTOM', 'SKIRT' UNION ALL
    SELECT 'BOTTOM', 'SHORT_PANTS' UNION ALL
    SELECT 'ACCESSORY', 'NECKLACE' UNION ALL
    SELECT 'ACCESSORY', 'RING' UNION ALL
    SELECT 'ACCESSORY', 'GLASSES'
) sub ON 1=1
JOIN (
    SELECT a.N + b.N * 10 + c.N * 100 + 1 AS seq
    FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
) nums ON 1=1
WHERE s.description = '트렌디한 패션 아이템을 판매합니다'
AND nums.seq <= 850;

-- 확인
SELECT '생성 완료' AS result,
       (SELECT COUNT(*) FROM users WHERE email LIKE 'stylehub%@stylehub.com') AS store_users,
       (SELECT COUNT(*) FROM stores WHERE description = '트렌디한 패션 아이템을 판매합니다') AS stores,
       (SELECT COUNT(*) FROM products WHERE description = '테스트 상품입니다') AS products;
