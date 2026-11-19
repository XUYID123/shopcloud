-- 同业务变更提交的失效记录；删除缓存失败可重试，不允许数据库成功后永久漏删。
CREATE TABLE permission_cache_invalidation (
    user_id BIGINT NOT NULL,
    key_prefix VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, key_prefix),
    KEY idx_permission_invalidation_retry (key_prefix, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
