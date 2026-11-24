ALTER TABLE order_main
    ADD COLUMN received_at DATETIME(3) NULL,
    ADD KEY idx_order_created (created_at,id);

CREATE TABLE order_shipment (
    order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    carrier_code VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tracking_no VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    shipped_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    allocation_snapshot JSON NOT NULL,
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_no) REFERENCES order_main(order_no)
) ENGINE=InnoDB;

CREATE TABLE order_shipment_request (
    order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id BIGINT NOT NULL,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (order_no,actor_id,request_key),
    CONSTRAINT fk_shipment_request FOREIGN KEY (order_no) REFERENCES order_shipment(order_no)
) ENGINE=InnoDB;
