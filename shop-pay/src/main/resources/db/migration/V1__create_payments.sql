CREATE TABLE payment_control (
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    amount_fen BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    close_requested BOOLEAN NOT NULL DEFAULT FALSE,
    refund_status VARCHAR(16) NULL,
    PRIMARY KEY (order_no),
    CHECK (amount_fen BETWEEN 0 AND 1000000000)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

CREATE TABLE payment_session (
    payment_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    provider_transaction_no VARCHAR(64) NULL,
    paid_at DATETIME(3) NULL,
    PRIMARY KEY (payment_no),
    UNIQUE KEY uk_payment_order (order_no),
    UNIQUE KEY uk_payment_txn (provider_transaction_no),
    FOREIGN KEY (order_no) REFERENCES payment_control(order_no)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

CREATE TABLE payment_request (
    user_id BIGINT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    PRIMARY KEY (user_id,request_key),
    FOREIGN KEY (payment_no) REFERENCES payment_session(payment_no)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

CREATE TABLE callback_inbox (
    event_id VARCHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    PRIMARY KEY (event_id),
    FOREIGN KEY (payment_no) REFERENCES payment_session(payment_no)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

CREATE TABLE callback_nonce (
    nonce VARCHAR(128) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (nonce),
    FOREIGN KEY (event_id) REFERENCES callback_inbox(event_id)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

CREATE TABLE mock_provider_event (
    event_id VARCHAR(64) NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    body MEDIUMBLOB NOT NULL,
    created_at DATETIME(3) NOT NULL,
    trace_id VARCHAR(64) NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    delivered_at DATETIME(3) NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_mock_payment (payment_no),
    KEY idx_mock_due (delivered_at,available_at,event_id),
    FOREIGN KEY (payment_no) REFERENCES payment_session(payment_no)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

CREATE TABLE payment_refund (
    refund_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    payment_no VARCHAR(64) NOT NULL,
    amount_fen BIGINT NOT NULL,
    reason VARCHAR(64) NOT NULL,
    refunded_at DATETIME(3) NOT NULL,
    PRIMARY KEY (refund_no),
    UNIQUE KEY uk_refund_order (order_no),
    FOREIGN KEY (payment_no) REFERENCES payment_session(payment_no)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

CREATE TABLE rocketmq_inbox (
    consumer_group VARCHAR(64) NOT NULL,
    event_id CHAR(36) NOT NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer_group,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;

CREATE TABLE rocketmq_outbox (
    event_id CHAR(36) NOT NULL,
    tag VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL,
    trace_id VARCHAR(64) NULL,
    body MEDIUMBLOB NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at DATETIME(3) NULL,
    lease_token CHAR(36) NULL,
    last_error VARCHAR(96) NULL,
    broker_message_id VARCHAR(128) NULL,
    PRIMARY KEY (event_id),
    KEY idx_outbox_due (sent_at,available_at,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=ascii COLLATE=ascii_bin;
