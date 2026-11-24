CREATE TABLE checkout_quote (
    quote_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    address_id BIGINT NOT NULL,
    address_snapshot JSON NOT NULL,
    item_snapshot JSON NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    goods_amount_fen BIGINT NOT NULL,
    freight_amount_fen BIGINT NOT NULL,
    payable_amount_fen BIGINT NOT NULL,
    total_weight_gram BIGINT NOT NULL,
    price_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    freight_rule_version VARCHAR(50) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NOT NULL,
    consumed_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (quote_id),
    KEY idx_checkout_quote_expiry (expires_at, quote_id),
    CONSTRAINT ck_checkout_quote_owner CHECK (user_id > 0 AND address_id > 0),
    CONSTRAINT ck_checkout_quote_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_checkout_quote_amount CHECK (
        goods_amount_fen >= 0 AND freight_amount_fen >= 0
        AND payable_amount_fen = goods_amount_fen + freight_amount_fen AND payable_amount_fen <= 1000000000
    ),
    CONSTRAINT ck_checkout_quote_weight CHECK (total_weight_gram BETWEEN 1 AND 500000)
) ENGINE=InnoDB;
