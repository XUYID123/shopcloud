-- 仅由 shop-product 的 Flyway 执行。业务记录与待删除任务在同一 MySQL 事务内提交。
CREATE TABLE cache_invalidation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    cache_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    execute_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_cache_invalidation_pending (done, id, execute_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
