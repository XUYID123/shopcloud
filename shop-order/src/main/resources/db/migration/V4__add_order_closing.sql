ALTER TABLE order_main
    ADD COLUMN close_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN refund_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_order_refund (refund_no),
    ADD KEY idx_order_status_created (order_status,created_at,id);

CREATE TABLE order_cancellation (
    cancellation_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(200) NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    failure_code VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    UNIQUE KEY uk_cancel_order (order_no),
    CONSTRAINT fk_cancellation_order FOREIGN KEY (order_no) REFERENCES order_main(order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
