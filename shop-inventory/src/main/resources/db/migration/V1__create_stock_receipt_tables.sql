-- 库存服务拥有独立数据源，sku_id 是商品服务的引用，不建立跨库外键。
CREATE TABLE sku_stock (
    sku_id BIGINT NOT NULL,
    warehouse_id VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    available BIGINT NOT NULL DEFAULT 0,
    reserved BIGINT NOT NULL DEFAULT 0,
    committed BIGINT NOT NULL DEFAULT 0,
    unavailable BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (sku_id, warehouse_id),
    CONSTRAINT ck_sku_stock_quantity CHECK (available >= 0 AND reserved >= 0 AND committed >= 0 AND unavailable >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE stock_batch (
    batch_id BIGINT NOT NULL AUTO_INCREMENT,
    sku_id BIGINT NOT NULL,
    warehouse_id VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_no VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    manufactured_on DATE NOT NULL,
    expires_on DATE NOT NULL,
    quality_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SELLABLE',
    initial_quantity INT NOT NULL,
    available BIGINT NOT NULL DEFAULT 0,
    reserved BIGINT NOT NULL DEFAULT 0,
    committed BIGINT NOT NULL DEFAULT 0,
    unavailable BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (batch_id),
    UNIQUE KEY uk_sku_warehouse_batch (sku_id,warehouse_id,batch_no),
    KEY idx_stock_batch_fefo (sku_id,warehouse_id,quality_status,expires_on,batch_id),
    CONSTRAINT fk_batch_stock FOREIGN KEY (sku_id,warehouse_id) REFERENCES sku_stock(sku_id,warehouse_id),
    CONSTRAINT ck_stock_batch_dates CHECK (expires_on > manufactured_on),
    CONSTRAINT ck_stock_batch_quality CHECK (quality_status IN ('SELLABLE','BLOCKED')),
    CONSTRAINT ck_stock_batch_quantity CHECK (initial_quantity BETWEEN 1 AND 1000000
        AND available >= 0 AND reserved >= 0 AND committed >= 0 AND unavailable >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE stock_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    operation_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NOT NULL,
    delta_available BIGINT NOT NULL,
    available_before BIGINT NOT NULL,
    available_after BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_ledger_operation (operation_no,batch_id,reason),
    KEY idx_stock_ledger_batch (batch_id,id),
    CONSTRAINT fk_stock_ledger_batch FOREIGN KEY (batch_id) REFERENCES stock_batch(batch_id),
    CONSTRAINT ck_stock_ledger_available CHECK (available_before >= 0 AND available_after >= 0
        AND available_after - available_before = delta_available)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 当前接口的固定方法/路径隐含在表名中。成功绑定无 TTL，保证重启和长时间后的重试安全。
CREATE TABLE stock_batch_receipt (
    actor_id BIGINT NOT NULL,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (actor_id,request_key),
    UNIQUE KEY uk_stock_receipt_batch (batch_id),
    CONSTRAINT fk_stock_receipt_batch FOREIGN KEY (batch_id) REFERENCES stock_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
