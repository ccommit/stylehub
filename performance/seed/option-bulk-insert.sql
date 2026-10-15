-- ============================================
-- StyleHub 상품 옵션 테스트 데이터 생성
-- 각 상품당 옵션 3개 (색상 × 사이즈)
-- ============================================

-- 상품 옵션 생성 (상품당 3개 옵션)
INSERT INTO products_options (product_id, color, size, stock_quantity, max_point_amount)
SELECT
    p.product_id,
    ELT(1 + FLOOR(RAND() * 5), 'BLACK', 'WHITE', 'NAVY', 'BEIGE', 'GRAY'),
    ELT(1 + FLOOR(RAND() * 5), 'S', 'M', 'L', 'XL', 'FREE'),
    FLOOR(10 + RAND() * 90),
    FLOOR(100 + RAND() * 900)
FROM products p
WHERE p.description = '테스트 상품입니다';

INSERT INTO products_options (product_id, color, size, stock_quantity, max_point_amount)
SELECT
    p.product_id,
    ELT(1 + FLOOR(RAND() * 5), 'RED', 'BLUE', 'GREEN', 'PINK', 'BROWN'),
    ELT(1 + FLOOR(RAND() * 5), 'S', 'M', 'L', 'XL', 'FREE'),
    FLOOR(10 + RAND() * 90),
    FLOOR(100 + RAND() * 900)
FROM products p
WHERE p.description = '테스트 상품입니다';

INSERT INTO products_options (product_id, color, size, stock_quantity, max_point_amount)
SELECT
    p.product_id,
    ELT(1 + FLOOR(RAND() * 5), 'BLACK', 'WHITE', 'NAVY', 'BEIGE', 'GRAY'),
    ELT(1 + FLOOR(RAND() * 5), '260', '270', '280', '290', 'FREE'),
    FLOOR(10 + RAND() * 90),
    FLOOR(100 + RAND() * 900)
FROM products p
WHERE p.description = '테스트 상품입니다';

-- 확인
SELECT
    (SELECT COUNT(*) FROM products WHERE description = '테스트 상품입니다') AS products,
    (SELECT COUNT(*) FROM products_options) AS options;
