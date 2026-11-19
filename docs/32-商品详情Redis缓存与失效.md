# 商品详情 Redis 缓存与失效

公开详情 GET `/api/v1/products/{productId}` 先校验十进制正整数 ID，再通过 StringRedisTemplate 读取 Redis。普通值用应用已有的 Jackson ObjectMapper 还原为 ProductCreatedVO。空标记 `__NULL__` 返回 PRODUCT_NOT_FOUND（404）。未命中时，商品和启用 SKU 在同一 MySQL 只读快照中读取，事务结束后再回填。普通 TTL 为 60 分钟加 0～600 秒随机抖动，空标记 TTL 为 3 分钟。库存字段继续为 null。

Redis 连接、超时或序列化失败会产生 `event=PRODUCT_CACHE_FALLBACK` 的 WARN 日志。日志记录商品 ID、操作和异常类型。读失败走 MySQL，写失败保留原数据库结果。ID 无效返回 400，数据库暂时故障返回 503。请求计数器 `product_cache_requests_total` 使用 `result=hit|miss|null_hit|fallback`，每次有效详情查询计一次。商品模块已有 Micrometer Core，有应用 MeterRegistry 时复用，否则使用进程内 SimpleMeterRegistry。查询日志、业务错误码及 Trace ID 沿用公共日志链。

## 服务归属和部署

键 `product:detail:{id}` 归 **shop-product** 所有，由 ProductCacheKeys 集中构造。所有商品实例共用同一 Redis 数据集。数据库表 `flour_product.cache_invalidation` 由商品服务的 Flyway V4 创建，消费者也运行在每个商品实例。其他服务通过商品 HTTP 接口获取详情或列表。

本地开发可复用 6381 业务 Redis。部署时可用 Spring Boot 的 `spring.data.redis.sentinel.master`、`spring.data.redis.sentinel.nodes` 或 `spring.data.redis.cluster.nodes` 配置集中式实例。按服务发放 Redis ACL 凭据，商品服务独享商品键前缀的写删权限，同一前缀上的读写落到 Redis 主节点。正常运行实例使用相同 Redis/MySQL，轮询默认每轮结束后等 500 毫秒。商品列表继续直查数据库。

## 商品写入和失效

| 写操作 | 同事务写入 |
|---|---|
| 商品上下架 | 两条 SALE_STATUS。execute_at 分别为数据库 NOW(3) 和 NOW(3) 加 1 秒 |
| 新增 SKU | 一条 SKU_CHANGE，execute_at 为数据库 NOW(3) |

待商品编辑、SKU 改价或启停、分类禁用上线后，分别登记 PRODUCT_EDIT、SKU_CHANGE、CATEGORY_DISABLED。分类禁用需要为受影响的商品分别登记详情失效。事件发布后续独立接入。

消费者按 ID 升序每批读取最多 50 条到期未完成记录。Redis DEL 成功后，执行 `UPDATE cache_invalidation SET done=1 WHERE id=? AND done=0`。DEL 返回键已不存在也算成功。实例重复确认时只会有一个更新影响一行。Redis 或确认失败留下待办，下次扫描继续处理。SQL 的 execute_at 决定第二次删除何时有资格执行，实际执行时间受轮询和积压影响。

异步失效存在短暂可见窗口。上下架的第二次删除覆盖常见的并发旧值回填，若旧读跨过两次删除，缓存仍以 TTL 兜底。持续 Redis 故障期间，读路径回源，恢复后处理持久化的删除任务。已完成记录保留在表中，长期运行需按运维策略归档或清理。

## 验收

1. `ProductDetailCacheTests` Mock 验证正常详情连查两次只访问一次 Mapper，并检查普通 TTL 抖动范围。
2. 同一测试 Mock 验证空值两查均为 404、仅回源一次、SET TTL 精确为 3 分钟。真 Redis 集成测试检查实际 TTL。手工自然到期验证用一个不存在的商品 ID 查一次，使用商品服务的开发 Redis 凭据查看键的 TTL。等待 180 秒后应显示键不存在，再查会重新短缓存。
3. Mock Redis GET、SET 失败，验证数据库结果照常返回、fallback 计数和 WARN 日志。异常内容本身不打印。
4. `ProductCacheInvalidationIntegrationTests` 在真实 MySQL 和 Redis 上验证下架同事务写两条记录、回滚两行都消失、数据库时间相差一秒、删除后详情返回 404。
5. 集成测试验证新增 SKU 写一条 SKU_CHANGE，删除后详情返回新增规格且库存为 null。
6. 集成测试让两个消费者对象同时读到同一行，并在同一真实 MySQL、Redis 上并发删除和条件确认。手工验证可启动两个商品服务进程，使用不同 server.port、同一商品库和 Redis。访问一次已上架商品，再新增 SKU，查询 Outbox 记录的 done 应最终为 1，再查详情应出现新 SKU。
7. 集成测试模拟 DEL 成功后确认失败，由其他消费者重试已不存在的 Redis 键并确认。手工验证可让实例 A 在 DEL 与数据库 UPDATE 之间的断点停住，以开发环境的进程控制终止 A。开启实例 B 的消费者后，done 应从 0 变成 1。验证过程中使用专门创建的测试商品。
8. 代码检查可用 `rg -n -F 'product:detail:' --glob '*.java' --glob '*.properties'` 核对键归属。以有元数据权限的开发账号查询 `information_schema.tables` 中 table_name 为 cache_invalidation 的记录，应仅看到商品库。
9. 运行 shop-common 和 shop-product Maven 回归，并用 Test-CategoryFlow.ps1 验证网关匿名商品详情和列表。脚本等待持久化任务消费后再断言下架商品隐藏。
