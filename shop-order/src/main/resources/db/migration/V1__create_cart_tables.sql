CREATE TABLE cart_owner (
    user_id BIGINT NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT ck_cart_owner_id CHECK (user_id > 0)
) ENGINE=InnoDB;

CREATE TABLE cart_item (
    user_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity SMALLINT NOT NULL,
    PRIMARY KEY (user_id, sku_id),
    CONSTRAINT fk_cart_item_owner FOREIGN KEY (user_id) REFERENCES cart_owner (user_id),
    CONSTRAINT ck_cart_item_sku CHECK (sku_id > 0),
    CONSTRAINT ck_cart_item_quantity CHECK (quantity BETWEEN 1 AND 100)
) ENGINE=InnoDB;
