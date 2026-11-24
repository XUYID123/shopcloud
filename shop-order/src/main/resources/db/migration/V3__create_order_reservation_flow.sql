CREATE TABLE user_order_quota (
    user_id BIGINT NOT NULL,
    active_count TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CHECK (active_count BETWEEN 0 AND 5)
) ENGINE=InnoDB;

CREATE TABLE order_main (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    quote_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    inventory_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    goods_amount_fen BIGINT NOT NULL,
    freight_amount_fen BIGINT NOT NULL,
    payable_amount_fen BIGINT NOT NULL,
    total_weight_gram BIGINT NOT NULL,
    address_snapshot JSON NOT NULL,
    item_snapshot JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at DATETIME(3) NULL,
    paid_at DATETIME(3) NULL,
    payment_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    exception_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_order_request (user_id,idempotency_key),
    UNIQUE KEY uk_order_quote (quote_id),
    KEY idx_order_owner_created (user_id,created_at,id),
    KEY idx_order_owner_status_created (user_id,order_status,created_at,id),
    KEY idx_order_status_expiry (order_status,expires_at,id),
    CONSTRAINT fk_order_quote FOREIGN KEY (quote_id) REFERENCES checkout_quote(quote_id)
) ENGINE=InnoDB;

CREATE TABLE order_cart_cleanup (
    order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (order_no),
    KEY idx_order_cart_pending (completed_at,order_no),
    CONSTRAINT fk_order_cart_cleanup FOREIGN KEY (order_no) REFERENCES order_main(order_no)
) ENGINE=InnoDB;

CREATE TABLE rocketmq_inbox (
    consumer_group VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group,event_id)
) ENGINE=InnoDB;

CREATE TABLE rocketmq_outbox (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tag VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL,
    trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    body MEDIUMBLOB NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at DATETIME(3) NULL,
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_error VARCHAR(96) NULL,
    broker_message_id VARCHAR(128) NULL,
    PRIMARY KEY (event_id),
    KEY idx_outbox_due (sent_at,available_at,event_id)
) ENGINE=InnoDB;
