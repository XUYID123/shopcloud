CREATE TABLE user_address (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    receiver VARCHAR(50) NOT NULL,
    phone CHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    region_code CHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    detail VARCHAR(200) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    default_user_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_default = 1 THEN user_id ELSE NULL END) STORED,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_user_address_owner (user_id, id),
    UNIQUE KEY uk_user_address_default (default_user_id),
    CONSTRAINT fk_user_address_account FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT ck_user_address_default CHECK (is_default IN (0, 1)),
    CONSTRAINT ck_user_address_receiver CHECK (CHAR_LENGTH(TRIM(receiver)) > 0),
    CONSTRAINT ck_user_address_phone CHECK (phone REGEXP '^1[3-9][0-9]{9}$'),
    CONSTRAINT ck_user_address_region CHECK (region_code REGEXP '^[0-9]{6}$'),
    CONSTRAINT ck_user_address_detail CHECK (CHAR_LENGTH(TRIM(detail)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 管理员可管理本人的收货地址，沿用 USER 的本人地址权限。
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM `role` r CROSS JOIN permission p
WHERE r.role_code = 'ADMIN' AND p.permission_code = 'address:write:own'
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
