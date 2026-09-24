-- =====================================================================
-- 커서 페이징 인덱스 운영 반영 DDL (MySQL 8)
--
-- 왜 필요한가
--   운영은 spring.jpa.hibernate.ddl-auto=validate 라 Product/Order 엔티티의 @Table(indexes) 선언이
--   운영 DB 에 인덱스를 만들지 않는다. 엔티티 선언은 테스트(H2)와 코드 문서화용이고, 운영에는 이 파일로 반영한다.
--
-- 대상 쿼리
--   products : ProductQueryRepository.findProductsWithCursor
--              WHERE [main_category = ? [AND sub_category = ?]] [AND user_id = ?] AND product_id < ?
--              ORDER BY product_id DESC LIMIT n
--   orders   : OrderQueryRepository.findMyOrdersWithCursor
--              WHERE user_id = ? AND order_id < ? ORDER BY order_id DESC LIMIT n
--
-- 실행 전 확인 필요 (확인하지 못한 사항)
--   운영 DB 에 같은 목적의 인덱스가 이미 있는지 확인하지 못했다.
--   특히 products.user_id, orders.user_id 는 외래키 제약이 있으면 MySQL 이 user_id 인덱스를 자동으로 만든다.
--   InnoDB 보조 인덱스는 PK 를 뒤에 포함하므로 그 인덱스가 (user_id, product_id)/(user_id, order_id) 와 같은 역할을 할 수 있다.
--   아래 조회로 먼저 확인하고, 같은 컬럼 순서의 인덱스가 이미 있으면 해당 CREATE 문은 실행하지 않는다.
--
--     SHOW INDEX FROM products;
--     SHOW INDEX FROM orders;
--
--   반영 후에는 EXPLAIN 으로 대상 쿼리가 새 인덱스를 쓰는지 확인한다(이 저장소에서 운영 EXPLAIN 결과는 확인하지 못했다).
--
-- 실행 방식
--   ALGORITHM=INPLACE, LOCK=NONE 으로 인덱스 생성 중에도 읽기·쓰기를 막지 않도록 요청한다.
--   서버가 이 조건으로 수행할 수 없으면 MySQL 이 에러를 내고 실행하지 않으므로, 그 경우 트래픽이 적은 시간에 다시 계획한다.
-- =====================================================================

-- 카테고리 필터 + 커서 범위 + product_id 정렬
CREATE INDEX idx_products_main_sub_product_id
    ON products (main_category, sub_category, product_id)
    ALGORITHM = INPLACE LOCK = NONE;

-- 스토어별 상품 목록 커서 페이징
CREATE INDEX idx_products_user_product_id
    ON products (user_id, product_id)
    ALGORITHM = INPLACE LOCK = NONE;

-- 내 주문 내역 커서 페이징
CREATE INDEX idx_orders_user_order_id
    ON orders (user_id, order_id)
    ALGORITHM = INPLACE LOCK = NONE;
