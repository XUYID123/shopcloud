CREATE TABLE inventory_reservation (
    order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    warehouse_id VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (order_no),
    CHECK (state IN ('PENDING','RESERVED','REJECTED','RELEASED','CONFIRMED'))
) ENGINE=InnoDB;

CREATE TABLE reservation_item (
    order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sku_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY (order_no,sku_id,batch_id),
    CONSTRAINT fk_reservation_item_order FOREIGN KEY (order_no) REFERENCES inventory_reservation(order_no),
    CONSTRAINT fk_reservation_item_batch FOREIGN KEY (batch_id) REFERENCES stock_batch(batch_id),
    CHECK (quantity BETWEEN 1 AND 100)
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
