# 数据库

MySQL 为事实来源，user 和 product 分别运行 Flyway，迁移版本在模块内递增。

- [user 迁移](../../shop-user/src/main/resources/db/migration)包含账号、角色、权限和两张关系表。账号名/角色码/权限码唯一，关联表使用外键和复合主键。权限失效表使用 user_id、key_prefix 去重。
- [product 迁移](../../shop-product/src/main/resources/db/migration)包含 category → product_spu → product_sku 的外键、唯一分类名与 SKU 编码、按启用态/分类/销售态查询的索引，以及缓存失效执行队列。
- [商品 SQL](../../shop-product/src/main/resources/mapper)使用共享锁校验关联记录，排他锁锁定 SPU，销售态写入使用 version CAS。业务写与失效记录同事务，多表只读快照使用 REPEATABLE READ。用户注册在同事务写账号、初始角色及权限失效任务。
- 迁移按扩展、兼容应用、可重试分批回填、验证、清理推进。已执行迁移保留原校验和。以隔离 MySQL 验证空库及旧结构升级、EXPLAIN、约束失败、回滚、并发。以业务唯一键与事务写保证持久幂等，明确重复结果、锁顺序、有界重试。
- 参见[数据库与消息](../messaging/database-consistency.md)。
