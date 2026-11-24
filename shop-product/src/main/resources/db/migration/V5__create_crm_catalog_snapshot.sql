CREATE TABLE crm_catalog_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    payload_sha256 BINARY(32) NOT NULL,
    payload JSON NOT NULL,
    imported_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_catalog_payload (payload_sha256),
    CONSTRAINT ck_crm_catalog_products CHECK (
        JSON_CONTAINS_PATH(payload, 'one', '$.products') = 1
        AND JSON_TYPE(JSON_EXTRACT(payload, '$.products')) = 'ARRAY'
        AND JSON_LENGTH(payload, '$.products') > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本机 CRM SPU/SKU 目录快照';
