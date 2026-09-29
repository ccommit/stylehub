-- =====================================================================
-- 스키마 베이스라인 — 현재 엔티티가 만드는 MySQL 스키마 (MySQL 8.0.46, 2026-09-29)
--
-- 무엇인가
--   운영은 ddl-auto=validate 이고 마이그레이션 도구가 없어, 운영 DB 에 실제로 어떤 유니크·외래 키·CHECK 제약이
--   걸려 있는지 저장소만으로는 알 수 없었다. 이 파일은 통합 테스트와 같은 조건(Testcontainers MySQL 8.0,
--   ddl-auto=create)에서 Hibernate 가 만든 테이블을 SHOW CREATE TABLE 로 떠 둔 것이다.
--   "DB 가 막는다"고 말하는 제약의 근거이자, 운영 스키마와 비교할 기준이다.
--
-- 운영과 비교하는 방법
--   운영 DB 에서 같은 방식으로 뜬 결과와 비교한다. 자동 생성 이름(FK..., UK...)은 환경마다 달라도 되고,
--   컬럼·유니크 대상 컬럼·외래 키 대상·CHECK 조건이 같은지를 본다.
--     SHOW CREATE TABLE <테이블>;
--   특히 아래 제약은 애플리케이션 규칙의 최종 방어선이라 운영에 반드시 있어야 한다.
--     user_coupons (user_id, coupon_event_id) UNIQUE  — 사용자당 쿠폰 1장
--     idempotency_records (user_id, operation, idempotency_key) UNIQUE — 멱등 키
--     orders.pg_order_id UNIQUE, payments.order_id UNIQUE — 주문당 PG 주문번호·결제 1건
--     CHECK 3종 — 운영 반영은 add-check-constraints.sql
--
-- 갱신
--   엔티티의 컬럼·제약을 바꾸면 이 파일도 다시 떠서 함께 커밋한다. 이 파일을 실행해 운영 스키마를 만들지는 않는다.
-- =====================================================================

CREATE TABLE `addresses` (
  `is_default` bit(1) NOT NULL,
  `address_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `zip_code` varchar(10) NOT NULL,
  `label` varchar(20) NOT NULL,
  `recipient_name` varchar(20) NOT NULL,
  `detail_address` varchar(40) DEFAULT NULL,
  `phone` varchar(40) NOT NULL,
  `street_address` varchar(40) NOT NULL,
  PRIMARY KEY (`address_id`),
  KEY `FK1fa36y2oqhao3wgg2rw1pi459` (`user_id`),
  CONSTRAINT `FK1fa36y2oqhao3wgg2rw1pi459` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `coupon_events` (
  `discount_value` int NOT NULL,
  `is_active` bit(1) NOT NULL,
  `issue_count` int NOT NULL,
  `issued_count` int NOT NULL,
  `min_order_amount` int NOT NULL,
  `coupon_event_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `expired_at` datetime(6) NOT NULL,
  `started_at` datetime(6) NOT NULL,
  `store_user_id` bigint DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `name` varchar(20) NOT NULL,
  `coupon_type` enum('PLATFORM','STORE') NOT NULL,
  `discount_type` enum('FIXED','RATE') NOT NULL,
  PRIMARY KEY (`coupon_event_id`),
  KEY `FKbe9btmuti3ppv5v4a1engq0b3` (`store_user_id`),
  CONSTRAINT `FKbe9btmuti3ppv5v4a1engq0b3` FOREIGN KEY (`store_user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `chk_coupon_events_issued_within_limit` CHECK ((`issued_count` <= `issue_count`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `idempotency_records` (
  `created_at` datetime(6) NOT NULL,
  `idempotency_record_id` bigint NOT NULL AUTO_INCREMENT,
  `resource_id` bigint DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `idempotency_key` varchar(64) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `operation` enum('ORDER_CREATE','PAYMENT_CANCEL') NOT NULL,
  PRIMARY KEY (`idempotency_record_id`),
  UNIQUE KEY `uk_idempotency_records_user_operation_key` (`user_id`,`operation`,`idempotency_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `order_details` (
  `quantity` int NOT NULL,
  `unit_price` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `order_detail_id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` bigint NOT NULL,
  `product_option_id` bigint NOT NULL,
  `user_coupon_id` bigint DEFAULT NULL,
  PRIMARY KEY (`order_detail_id`),
  KEY `FKjyu2qbqt8gnvno9oe9j2s2ldk` (`order_id`),
  KEY `FKdfp2uc5k6wgd1ehyoq68f11vc` (`product_option_id`),
  KEY `FKs6puogogb3u36rsnha15sqew8` (`user_coupon_id`),
  CONSTRAINT `FKdfp2uc5k6wgd1ehyoq68f11vc` FOREIGN KEY (`product_option_id`) REFERENCES `products_options` (`product_option_id`),
  CONSTRAINT `FKjyu2qbqt8gnvno9oe9j2s2ldk` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`),
  CONSTRAINT `FKs6puogogb3u36rsnha15sqew8` FOREIGN KEY (`user_coupon_id`) REFERENCES `user_coupons` (`user_coupon_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `orders` (
  `discount_amount` int NOT NULL,
  `earned_point` int NOT NULL,
  `used_point` int NOT NULL,
  `address_id` bigint NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `delivered_at` datetime(6) DEFAULT NULL,
  `order_id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `pg_order_id` varchar(64) NOT NULL,
  `order_status` enum('CANCELLED','DELIVERED','PAID','PENDING','PREPARING','SHIPPING') NOT NULL,
  PRIMARY KEY (`order_id`),
  UNIQUE KEY `UKomvh641kjpmy47d6h4sih82vh` (`pg_order_id`),
  KEY `idx_orders_user_order_id` (`user_id`,`order_id`),
  KEY `idx_orders_status_order_id` (`order_status`,`order_id`),
  KEY `FKhlglkvf5i60dv6dn397ethgpt` (`address_id`),
  CONSTRAINT `FK32ql8ubntj5uh44ph9659tiih` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
  CONSTRAINT `FKhlglkvf5i60dv6dn397ethgpt` FOREIGN KEY (`address_id`) REFERENCES `addresses` (`address_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `payment_refund_failures` (
  `attempt_count` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `refund_failure_id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) DEFAULT NULL,
  `pg_order_id` varchar(64) NOT NULL,
  `payment_key` varchar(200) NOT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `status` enum('PENDING','RESOLVED') NOT NULL,
  PRIMARY KEY (`refund_failure_id`),
  UNIQUE KEY `uk_payment_refund_failures_payment_key` (`payment_key`),
  KEY `idx_payment_refund_failures_status` (`status`,`refund_failure_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `payments` (
  `approved_amount` int NOT NULL,
  `balance_amount` int NOT NULL,
  `cancel_amount` int DEFAULT NULL,
  `requested_amount` int NOT NULL,
  `total_amount` int NOT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `order_id` bigint NOT NULL,
  `payment_id` bigint NOT NULL AUTO_INCREMENT,
  `requested_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `order_name` varchar(100) NOT NULL,
  `cancel_reason` varchar(200) DEFAULT NULL,
  `payment_key` varchar(200) NOT NULL,
  `status` enum('ABORTED','CANCELED','DONE','EXPIRED','IN_PROGRESS','PARTIAL_CANCELED','READY','WAITING_FOR_DEPOSIT') NOT NULL,
  PRIMARY KEY (`payment_id`),
  UNIQUE KEY `UK8vo36cen604as7etdfwmyjsxt` (`order_id`),
  CONSTRAINT `FK81gagumt0r8y3rmudcgpbk42l` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `point_histories` (
  `amount` int NOT NULL,
  `balance_snapshot` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `order_id` bigint DEFAULT NULL,
  `point_id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `point_type` enum('DAILY_LOGIN','EARN','USE','WELCOME') NOT NULL,
  PRIMARY KEY (`point_id`),
  KEY `idx_point_histories_user_point_id` (`user_id`,`point_id`),
  KEY `FK4yklqh8xyuhs24sp910tocq0l` (`order_id`),
  CONSTRAINT `FK4yklqh8xyuhs24sp910tocq0l` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`),
  CONSTRAINT `FKp1n0qput9f88pa5lwy2nsr19f` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `products` (
  `like_count` int DEFAULT NULL,
  `price` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `product_id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `name` varchar(20) NOT NULL,
  `image_url` varchar(300) NOT NULL,
  `description` text NOT NULL,
  `main_category` enum('ACCESSORY','BOTTOM','SHOES','TOP') NOT NULL,
  `sub_category` enum('DENIM_PANTS','DRESS_SHOES','GLASSES','JACKET','NECKLACE','RING','RUNNING_SHOES','SHORT_PANTS','SKIRT','SNEAKERS','SWEATSHIRT','T_SHIRT') NOT NULL,
  PRIMARY KEY (`product_id`),
  KEY `idx_products_main_sub_product_id` (`main_category`,`sub_category`,`product_id`),
  KEY `idx_products_user_product_id` (`user_id`,`product_id`),
  CONSTRAINT `FKdb050tk37qryv15hd932626th` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `products_options` (
  `max_point_amount` int DEFAULT NULL,
  `stock_quantity` int NOT NULL,
  `product_id` bigint NOT NULL,
  `product_option_id` bigint NOT NULL AUTO_INCREMENT,
  `size` varchar(10) DEFAULT NULL,
  `color` varchar(20) DEFAULT NULL,
  PRIMARY KEY (`product_option_id`),
  KEY `FKieamgvjmxt9yms29capsi12d6` (`product_id`),
  CONSTRAINT `FKieamgvjmxt9yms29capsi12d6` FOREIGN KEY (`product_id`) REFERENCES `products` (`product_id`),
  CONSTRAINT `chk_products_options_stock_nonnegative` CHECK ((`stock_quantity` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `user_coupons` (
  `coupon_event_id` bigint NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `user_coupon_id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `status` enum('UNUSED','USED') NOT NULL,
  PRIMARY KEY (`user_coupon_id`),
  UNIQUE KEY `UKso8iwvots36od7s0aryw05208` (`user_id`,`coupon_event_id`),
  KEY `FK5km8ipp37tpw7mcxikaiqduuy` (`coupon_event_id`),
  CONSTRAINT `FK5km8ipp37tpw7mcxikaiqduuy` FOREIGN KEY (`coupon_event_id`) REFERENCES `coupon_events` (`coupon_event_id`),
  CONSTRAINT `FK654lvm2qu8l08pyg310mbd74h` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `users` (
  `birth_date` date DEFAULT NULL,
  `is_active` bit(1) NOT NULL,
  `last_login_date` date DEFAULT NULL,
  `point_balance` int NOT NULL,
  `store_like_count` int DEFAULT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `store_deleted_at` datetime(6) DEFAULT NULL,
  `total_spent` bigint NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(20) NOT NULL,
  `store_name` varchar(20) DEFAULT NULL,
  `email` varchar(100) NOT NULL,
  `provider_user_id` varchar(100) DEFAULT NULL,
  `password` varchar(400) DEFAULT NULL,
  `store_description` varchar(400) DEFAULT NULL,
  `grade` enum('BRONZE','GOLD','SILVER') NOT NULL,
  `provider` enum('GOOGLE') DEFAULT NULL,
  `role` enum('ADMIN','STORE','USER') NOT NULL,
  `store_status` enum('APPROVED','PENDING','REJECTED','SUSPENDED') DEFAULT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `UK3g1j96g94xpk3lpxl2qbl985x` (`name`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`),
  CONSTRAINT `chk_users_point_nonnegative` CHECK ((`point_balance` >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

