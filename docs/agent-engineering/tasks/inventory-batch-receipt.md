# 库存批次入库开发记录

ROUTE-05。范围为建立独立 `shop-inventory` 服务并交付 `POST /api/v1/admin/inventory/batches`。库存预占与订单状态不在本次范围。对外接口契约在交付回复提供。

| 项目 | 决定 |
|---|---|
| MySQL | REQUIRED。库存独立 schema，写入锁顺序为请求键、SKU 汇总、批次、流水。唯一键约束操作人幂等键及 SKU/仓库/批号。锁内不等待商品远程调用。|
| Redis | NOT_REQUIRED。库存幂等持久化，复用网关现有防刷和动态权限，不增加库存 Redis 键。|
| RocketMQ | NOT_REQUIRED。当前是单服务库存写操作，无下游事件契约。|
| 日志及异常 | REQUIRED。复用 Result、BizLog、Trace、统一业务异常。已知依赖故障保留原因并返回 503。|
| 参数及权限 | REQUIRED。校验数量、单仓、批次和日期。按上海业务日判断；网关动态 `inventory:write` 规则与内部 JWT 保护入口。库存到商品复用 OpenFeign 和 Trace，单独 RSA 密钥仅有入库元数据读取能力。|
| 性能及测试 | REQUIRED。查询使用主键/唯一索引，事务不含网络调用；验证实际入口、MySQL 持久幂等、批号竞争、失败回滚和独立签名信任边界。|

方案审查采用现有业务规划。商品查询需覆盖未上架 SKU，因为正常链路先备货再上架。库存服务通过限定范围的短期服务 JWT 获取商品保质期；网关私钥仍只在网关，跨服务无直查对方数据源。接口重放返回首次创建的快照，过期规则与商品可用性只在首次创建时验证。批次初始为 SELLABLE，业务日期取 Asia/Shanghai。

## 实现和发布顺序

- [库存模块](../../../shop-inventory/pom.xml)加入 Maven reactor，继承业务父 POM。Flyway 在独立 `flour_inventory` 创建 `sku_stock`、`stock_batch`、`stock_ledger`、`stock_batch_receipt`。本地服务使用 8086，Compose 映射 MySQL 到 3309。
- 公共库新增可选的限定方法和路径的服务身份验签。商品服务通过 `SHOP_PRODUCT_INVENTORY_LOOKUP_ENABLED=true` 启用保质期读取并加载库存服务公钥，库存服务持有其独立私钥。Nacos 发现商品服务；本地也可显式配置 `SHOP_INVENTORY_PRODUCT_URL`。生产私钥只挂载到库存签发实例。
- 先发布 user 的 Flyway `V4` 授权迁移和商品元数据能力，再启用库存服务与网关 `shop-inventory-api` 路由。按现有 Redis 版本比较流程发布 `inventory:write` 规则，本地发布器为 `infra/security/Enable-InventoryRule.ps1`。原有管理员权限快照按其 TTL 刷新。
- 复用 common 的 `Result`、`GlobalExceptionHandler`、`BizLog`、内部身份和 Feign Trace。库存写操作输出 `STOCK_BATCH_RECEIVE`，成功时用生成的批次 ID 关联访问日志。`docs/openapi.json` 更新已实现状态和 200 重放语义。

## 审查与验证

- Flyway 从空库存库应用 V1。MySQL `EXPLAIN` 验证幂等记录、批次快照和 SKU 汇总都走 `PRIMARY`，访问类型均为 `const`，估算各 1 行。检查数据在事务内回滚。
- 本地 Compose 配置解析、PowerShell 脚本语法检查和静态门禁通过。幂等绑定由数据库持久化，库存流水与余额受同一事务保护。
- 本地 `mvn ... -pl shop-inventory,shop-gateway,shop-product,shop-user -am verify -Dspring.cloud.nacos.discovery.enabled=false` 中 common 46、user 62、gateway 91、product 52 项通过。库存用例发现 Jackson 将数字 SKU ID 转成了字符串，修正输入后单独复跑 `mvn ... -pl shop-inventory -am -Dtest=StockBatchIntegrationTests,ScopedServiceJwtTests -Dsurefire.failIfNoSpecifiedTests=false verify`，6 个库存集成测试和 1 个已计入 common 的服务 JWT 用例通过。
- 库存集成测试使用真实 MySQL、库存 HTTP 入口和验签商品替身。覆盖同键并发、重复批号回滚、同 SKU 多批次正确累加、原始快照重放、日期规则、下游错误分类、匿名及过期凭据。代码审查核对事务内外边界、所有参数绑定、签名作用域和兼容性。
- 使用新包执行 `infra/security/Test-CategoryFlow.ps1 -IncludeInventory`。真实网关、用户、库存、商品与本地 MySQL、Redis 冒烟通过。管理员入库未上架 SKU 返回 201，重放返回 200 且聚合仍为 100，匿名/普通用户及两处直连被拒绝，网关、库存和商品访问日志以及库存业务日志复用请求 Trace ID。结果为 `target/category-smoke/result.json`。
- 本地端到端采用静态服务发现，未验证 Nacos 环境、生产密钥分发或容量压测。库存及测试数据无跨库查询。本次没有库存预占、扣减或订单消息。
