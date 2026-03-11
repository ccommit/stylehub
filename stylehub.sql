-- ========================
-- StyleHub 최종 스키마
-- ========================

CREATE TABLE `users` (
	`user_id`          BIGINT       NOT NULL COMMENT '사용자 고유 ID',
	`name`             VARCHAR(20)  NOT NULL,
	`email`            VARCHAR(100) NOT NULL COMMENT '로그인 이메일(중복 불가)',
	`password`         VARCHAR(400) NOT NULL COMMENT 'Bycript해시(소셜 로그인유저는 NULL)',
	`role`             ENUM('USER','STORE','ADMIN') NOT NULL DEFAULT 'USER' COMMENT 'USER | STORE | ADMIN',
	`grade`            ENUM('BRONZE','SILVER','GOLD') NOT NULL DEFAULT 'BRONZE' COMMENT 'BRONZE | SILVER | GOLD',
	`total_spent`      BIGINT       NOT NULL DEFAULT 0 COMMENT '누적구매금액(등급 산정용)',
	`point_balance`    INT          NOT NULL DEFAULT 0 COMMENT '현재 포인트 잔액',
	`login_point_date` DATE         NULL COMMENT '마지막 로그인 포인트 적립일(일 1회 10P)',
	`is_active`        BOOLEAN      NOT NULL DEFAULT TRUE COMMENT '계정 활성화(sot_delete)용',
	`birth_date`       DATETIME     NULL COMMENT '생년월일(생일 쿠폰 발급용)',
	`created_at`       DATETIME     NOT NULL DEFAULT NOW() COMMENT '가입일시',
	`updated_at`       DATETIME     NULL COMMENT '수정일시'
);

CREATE TABLE `stores` (
	`store_id`    BIGINT       NOT NULL COMMENT '스토어(브랜드)고유 ID',
	`user_id`     BIGINT       NOT NULL COMMENT '사용자 고유 ID',
	`name`        VARCHAR(20)  NOT NULL COMMENT '스토어명',
	`description` VARCHAR(400) NOT NULL COMMENT '스토어 소개',
	`like`        INT          NOT NULL DEFAULT 0,
	`status`      ENUM('PENDING','APPROVED','SUSPENDED','REJECTED') NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING(심사중) → APPROVED(승인) → SUSPENDED(정지) → REJECTED(거절)',
	`created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '입점 신청일시',
	`approved_at` DATETIME     NULL COMMENT '승인일시(PENDING이면 NULL)',
	`updated_at`  DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
	`deleted_at`  DATETIME     NULL COMMENT '논리 삭제용 (Null이면 미삭제, 값이 있으면 삭제 시점)'
);

CREATE TABLE `addresses` (
	`address_id`     BIGINT      NOT NULL COMMENT '배송지_고유_번호',
	`user_id`        BIGINT      NOT NULL COMMENT '사용자 고유 ID',
	`label`          VARCHAR(20) NOT NULL COMMENT '배송지 별칭 (집/회사 등',
	`recipient_name` VARCHAR(20) NOT NULL COMMENT '수령인 이름',
	`phone`          VARCHAR(40) NOT NULL COMMENT '수령인_연락처',
	`zip_code`       VARCHAR(10) NOT NULL COMMENT '우편번호',
	`street_address` VARCHAR(40) NOT NULL COMMENT '기본주소',
	`detail_address` VARCHAR(40) NULL COMMENT '상세주소',
	`is_default`     BOOLEAN     NOT NULL DEFAULT FALSE COMMENT '기본 배송지 여부',
	`created_at`     DATETIME    NOT NULL COMMENT '생성일시',
	`updated_at`     DATETIME    NOT NULL COMMENT '수정일시',
	`deleted_at`     DATETIME    NULL COMMENT '삭제일시'
);

CREATE TABLE `products` (
	`product_id`    BIGINT       NOT NULL COMMENT '상품고유ID',
	`store_id`      BIGINT       NOT NULL COMMENT '스토어(브랜드)고유 ID',
	`name`          VARCHAR(20)  NOT NULL COMMENT '상품명',
	`main_category` ENUM         NOT NULL,
	`sub_category`  ENUM         NOT NULL,
	`description`   TEXT         NOT NULL COMMENT '상품설명',
	`price`         INT          NOT NULL COMMENT '상품가격',
	`image_url`     VARCHAR(300) NOT NULL COMMENT '상품이미지 URL',
	`like_count`    INT          NULL DEFAULT 0 COMMENT '좋아요수',
	`created_at`    DATETIME     NOT NULL,
	`updated_at`    DATETIME     NULL
);

CREATE TABLE `products_options` (
	`product_option_id` BIGINT      NOT NULL COMMENT '상품옵션아이디',
	`product_id`        BIGINT      NOT NULL COMMENT '상품고유ID',
	`color`             VARCHAR(20) NULL COMMENT '상품 색상',
	`size`              VARCHAR(10) NULL COMMENT '사이즈',
	`stock_quantity`    INT         NOT NULL DEFAULT 0,
	`extra_option`      VARCHAR(20) NULL,
	`additional_price`  INT         NOT NULL DEFAULT 0
);

CREATE TABLE `coupons` (
	`coupon_id`        BIGINT      NOT NULL COMMENT '쿠폰고유ID',
	`store_id`         BIGINT      NOT NULL COMMENT '스토어(브랜드)고유 ID',
	`issued_by`        BIGINT      NOT NULL COMMENT '사용자 고유 ID',
	`name`             VARCHAR(20) NOT NULL COMMENT '쿠폰명',
	`discount_type`    ENUM('FIXED','RATE') NOT NULL COMMENT '할인 유형 (FIXED / RATE)',
	`discount_value`   INT         NOT NULL COMMENT '할인금액',
	`min_order_amount` INT         NOT NULL DEFAULT 0,
	`max_issue_count`  INT         NOT NULL,
	`issued_count`     INT         NOT NULL,
	`started_at`       DATETIME    NOT NULL,
	`expired_at`       DATETIME    NOT NULL,
	`is_active`        BOOLEAN     NOT NULL DEFAULT TRUE COMMENT '활성여부',
	`created_at`       DATETIME    NOT NULL DEFAULT NOW() COMMENT '등록일시',
	`updated_at`       DATETIME    NULL COMMENT '수정일시'
);

CREATE TABLE `user_coupons` (
	`user_coupon_id` BIGINT   NOT NULL,
	`user_id`        BIGINT   NOT NULL COMMENT '사용자 고유 ID',
	`coupon_id`      BIGINT   NOT NULL COMMENT '쿠폰고유ID',
	`status`         ENUM('AVAILABLE','USED','EXPIRED') NOT NULL COMMENT '쿠폰사용상태',
	`used_at`        DATETIME NULL COMMENT '사용일시',
	`downloaded_at`  DATETIME NULL
);

CREATE TABLE `orders` (
	`order_id`             BIGINT       NOT NULL COMMENT '주문고유ID',
	`user_id`              BIGINT       NOT NULL COMMENT '사용자 고유 ID',
	`address_id`           BIGINT       NOT NULL COMMENT '배송지_고유_번호',
	`toss_order_id`        VARCHAR(100) NOT NULL,
	`order_status`         ENUM('PENDING','PAID','PREPARING','SHIPPING','DELIVERED','CANCELLED') NOT NULL COMMENT '주문 상태',
	`delivery_status`      ENUM('PREPARING','SHIPPING','DELIVERED') NULL COMMENT '배송 상태',
	`total_product_amount` INT          NOT NULL,
	`discount_amount`      INT          NOT NULL DEFAULT 0,
	`final_amount`         INT          NOT NULL DEFAULT 0,
	`used_point`           INT          NOT NULL DEFAULT 0,
	`earned_point`         INT          NOT NULL DEFAULT 0,
	`ordered_at`           DATETIME     NOT NULL,
	`updated_at`           DATETIME     NULL
);

CREATE TABLE `order_items` (
	`order_items_id`    BIGINT      NOT NULL,
	`product_option_id` BIGINT      NOT NULL COMMENT '상품옵션아이디',
	`order_id`          BIGINT      NOT NULL COMMENT '주문고유ID',
	`product_id`        BIGINT      NOT NULL COMMENT '상품고유ID',
	`user_coupon_id`    BIGINT      NULL,
	`product_name`      VARCHAR(20) NOT NULL COMMENT '주문 시점 스냅샷용',
	`item_total_amount` INT         NOT NULL,
	`unit_price`        INT         NOT NULL,
	`quantity`          INT         NOT NULL,
	`created_at`        DATETIME    NOT NULL
);

CREATE TABLE `payments` (
	`payment_id`       BIGINT       NOT NULL COMMENT '결제 ID',
	`order_id`         BIGINT       NOT NULL COMMENT '주문고유ID',
	`payment_key`      VARCHAR(200) NOT NULL,
	`order_name`       VARCHAR(20)  NOT NULL,
	`requested_amount` INT          NOT NULL,
	`total_amount`     INT          NOT NULL COMMENT '실제 결제금액 (위변조 검증)',
	`approved_amount`  INT          NOT NULL,
	`status`           ENUM('READY','IN_PROGRESS','DONE','CANCELED','PARTIAL_CANCELED','FAILED') NOT NULL DEFAULT 'READY' COMMENT 'READY | IN_PROGRESS | DONE | CANCELED | PARTIAL_CANCELED | FAILED',
	`approved_at`      DATETIME     NULL,
	`updated_at`       DATETIME     NOT NULL,
	`cancel_amount`    INT          NULL,
	`cancel_reason`    VARCHAR(200) NULL,
	`requested_at`     DATETIME     NOT NULL,
	`balance_amount`   INT          NOT NULL
);

CREATE TABLE `point_histories` (
	`point_id`         BIGINT   NOT NULL,
	`user_id`          BIGINT   NOT NULL COMMENT '사용자 고유 ID',
	`order_id`         BIGINT   NULL,
	`point_type`       ENUM('EARN','USE','EXPIRE','WELCOME','DAILY_LOGIN') NOT NULL COMMENT 'EARN | USE | EXPIRE | WELCOME | DAILY_LOGIN',
	`amount`           INT      NOT NULL COMMENT '양수: 적립 / 음수: 차감',
	`balance_snapshot` INT      NOT NULL COMMENT '변동후 잔액',
	`created_at`       DATETIME NOT NULL
);

-- ========================
-- PRIMARY KEY
-- ========================

ALTER TABLE `users`            ADD CONSTRAINT `PK_USERS`            PRIMARY KEY (`user_id`);
ALTER TABLE `stores`           ADD CONSTRAINT `PK_STORES`           PRIMARY KEY (`store_id`);
ALTER TABLE `addresses`        ADD CONSTRAINT `PK_ADDRESSES`        PRIMARY KEY (`address_id`);
ALTER TABLE `products`         ADD CONSTRAINT `PK_PRODUCTS`         PRIMARY KEY (`product_id`);
ALTER TABLE `products_options`  ADD CONSTRAINT `PK_PRODUCTS_OPTIONS` PRIMARY KEY (`product_option_id`);
ALTER TABLE `coupons`          ADD CONSTRAINT `PK_COUPONS`          PRIMARY KEY (`coupon_id`);
ALTER TABLE `user_coupons`     ADD CONSTRAINT `PK_USER_COUPONS`     PRIMARY KEY (`user_coupon_id`);
ALTER TABLE `orders`           ADD CONSTRAINT `PK_ORDERS`           PRIMARY KEY (`order_id`);
ALTER TABLE `order_items`      ADD CONSTRAINT `PK_ORDER_ITEMS`      PRIMARY KEY (`order_items_id`);
ALTER TABLE `payments`         ADD CONSTRAINT `PK_PAYMENTS`         PRIMARY KEY (`payment_id`);
ALTER TABLE `point_histories`  ADD CONSTRAINT `PK_POINT_HISTORIES`  PRIMARY KEY (`point_id`);

-- ========================
-- FK 및 UNIQUE KEY
-- ========================

-- stores → users (1:1)
ALTER TABLE `stores`
	ADD CONSTRAINT `fk_stores_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`),
	ADD UNIQUE KEY `uq_stores_user` (`user_id`);

-- addresses → users (1:N)
ALTER TABLE `addresses`
	ADD CONSTRAINT `fk_addresses_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);

-- products → stores (1:N)
ALTER TABLE `products`
	ADD CONSTRAINT `fk_products_store` FOREIGN KEY (`store_id`) REFERENCES `stores` (`store_id`);

-- products_options → products (1:N)
ALTER TABLE `products_options`
	ADD CONSTRAINT `fk_products_options_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`product_id`);

-- coupons → stores, users (1:N)
ALTER TABLE `coupons`
	ADD CONSTRAINT `fk_coupons_store`     FOREIGN KEY (`store_id`)  REFERENCES `stores` (`store_id`),
	ADD CONSTRAINT `fk_coupons_issued_by` FOREIGN KEY (`issued_by`) REFERENCES `users`  (`user_id`);

-- user_coupons → users, coupons (M:N 해소)
ALTER TABLE `user_coupons`
	ADD CONSTRAINT `fk_user_coupons_user`   FOREIGN KEY (`user_id`)   REFERENCES `users`   (`user_id`),
	ADD CONSTRAINT `fk_user_coupons_coupon` FOREIGN KEY (`coupon_id`) REFERENCES `coupons` (`coupon_id`),
	ADD UNIQUE KEY `uq_user_coupons`        (`user_id`, `coupon_id`);

-- orders → users, addresses (1:N)
ALTER TABLE `orders`
	ADD CONSTRAINT `fk_orders_user`    FOREIGN KEY (`user_id`)    REFERENCES `users`     (`user_id`),
	ADD CONSTRAINT `fk_orders_address` FOREIGN KEY (`address_id`) REFERENCES `addresses` (`address_id`);

-- order_items → orders, products, products_options, user_coupons
ALTER TABLE `order_items`
	ADD CONSTRAINT `fk_order_items_order`          FOREIGN KEY (`order_id`)          REFERENCES `orders`           (`order_id`),
	ADD CONSTRAINT `fk_order_items_product`        FOREIGN KEY (`product_id`)        REFERENCES `products`         (`product_id`),
	ADD CONSTRAINT `fk_order_items_product_option` FOREIGN KEY (`product_option_id`) REFERENCES `products_options` (`product_option_id`),
	ADD CONSTRAINT `fk_order_items_user_coupon`    FOREIGN KEY (`user_coupon_id`)    REFERENCES `user_coupons`     (`user_coupon_id`);

-- payments → orders (1:1)
ALTER TABLE `payments`
	ADD CONSTRAINT `fk_payments_order`       FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`),
	ADD UNIQUE KEY `uq_payments_order`       (`order_id`),
	ADD UNIQUE KEY `uq_payments_payment_key` (`payment_key`);

-- point_histories → users, orders (1:N)
ALTER TABLE `point_histories`
	ADD CONSTRAINT `fk_point_histories_user`  FOREIGN KEY (`user_id`)  REFERENCES `users`  (`user_id`),
	ADD CONSTRAINT `fk_point_histories_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`);
