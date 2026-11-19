-- SKU 是独立销售规格；价格使用整数分，重量使用整数克，库存由后续库存业务维护。
CREATE TABLE product_sku (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'SKU主键',
    product_id BIGINT NOT NULL COMMENT '所属SPU',
    sku_code VARCHAR(50) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL COMMENT '全局编码，不区分大小写',
    spec_name VARCHAR(100) NOT NULL COMMENT '规格展示名称',
    weight_gram BIGINT NOT NULL COMMENT '每袋净重，克',
    price_fen BIGINT NOT NULL COMMENT '每袋售价，分',
    enabled BOOLEAN NOT NULL COMMENT '规格是否启用，不等于SPU上架',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '后续更新使用的乐观锁版本',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_code (sku_code),
    KEY idx_sku_product_enabled (product_id, enabled, id),
    CONSTRAINT fk_sku_product FOREIGN KEY (product_id) REFERENCES product_spu(id),
    CONSTRAINT ck_sku_weight CHECK (weight_gram BETWEEN 100 AND 50000),
    CONSTRAINT ck_sku_price CHECK (price_fen BETWEEN 1 AND 10000000),
    CONSTRAINT ck_sku_enabled CHECK (enabled IN (0,1)),
    CONSTRAINT ck_sku_version CHECK (version >= 1),
    CONSTRAINT ck_sku_code CHECK (sku_code REGEXP '^[A-Za-z0-9_-]{1,50}$'),
    CONSTRAINT ck_sku_spec CHECK (CHAR_LENGTH(TRIM(spec_name)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品销售规格SKU';
