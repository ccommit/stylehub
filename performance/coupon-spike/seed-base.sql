-- 판매자 1명(승인) + 상품 10,000개(옵션 1개씩). 상품 목록 배경 부하용.
DELETE FROM products_options; DELETE FROM products; DELETE FROM users WHERE email='spike_store@perf.test';
INSERT INTO users (name,email,password,role,grade,total_spent,point_balance,is_active,store_name,store_status,approved_at,created_at,updated_at)
VALUES ('spike_store','spike_store@perf.test','$2y$10$IaHd2MOHqgjP4HhXBijo4uJ00fPrcZLNX7hMTkUVJHhPygbJSYUpO','STORE','BRONZE',0,0,true,'spike store','APPROVED',NOW(),NOW(),NOW());
SET @s := LAST_INSERT_ID();
INSERT INTO products (user_id,name,description,image_url,price,main_category,sub_category,like_count,created_at,updated_at)
SELECT @s, CONCAT('p', n), 'spike', 'http://img/x.png', 10000 + (n % 50) * 1000,
       ELT(1 + n % 4,'TOP','BOTTOM','SHOES','ACCESSORY'), ELT(1 + n % 4,'T_SHIRT','DENIM_PANTS','SNEAKERS','RING'), 0, NOW(), NOW()
FROM (SELECT a.N + b.N*10 + c.N*100 + d.N*1000 + 1 AS n FROM
 (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
 (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
 (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,
 (SELECT 0 N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d) x;
INSERT INTO products_options (product_id,color,size,stock_quantity,max_point_amount)
SELECT product_id,'BLACK','M',1000,0 FROM products;
SELECT (SELECT COUNT(*) FROM products) products, (SELECT COUNT(*) FROM products_options) options;
