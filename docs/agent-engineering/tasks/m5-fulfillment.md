# M5 履约与联调

2026-11-24 · ROUTE-05 · COMPLEX / HIGH

## 需求与方案

按[业务规则](../../02-业务与数据设计.md#34-发货和收货)和[接口契约](../../03-接口文档.md)交付后台订单列表、详情、发货及买家确认收货。采用 M5 验收允许的完整 HTTP 演示方式。后台待发货详情展示库存当前批次分配，已发货订单展示发货时的批次快照。

| 评估点 | 判断与措施 |
| --- | --- |
| MySQL、并发与幂等 | REQUIRED。追加 V5。为后台全量游标查询补 `(created_at,id)` 索引，状态过滤复用 V4 索引，订单号用唯一索引。事务先锁订单行，再写唯一物流单、批次快照和按管理员/订单/键去重的发货请求，最后同步更新订单。重复请求返回当前订单；同键异内容返回 409。收货只锁本人订单，首次写入时间，之后返回原时间。 |
| 权限与参数 | REQUIRED。使用签名网关身份、后台 `order:read:any`、`order:ship` 及本人 `order:read:own` 规则。订单服务检查收货归属。库存分配查询使用独立作用域 JWT。校验游标、订单号、幂等键及请求体。 |
| 异常与日志 | REQUIRED。复用 Result、BusinessException、Trace 和 BizLog，日志只记录订单号和操作结果。依赖故障时发货和状态写入保持可重试。 |
| Redis | NOT_REQUIRED。履约状态、批次快照与幂等记录来自 MySQL；复用网关既有权限缓存。 |
| RocketMQ | NOT_REQUIRED。履约状态本地提交，复用 M4 已确认的库存及支付事实。 |
| 联调 | REQUIRED。隔离环境验证已支付订单至 COMPLETED、冻结和到期批次、权限、游标、并发发货与收货。 |

## 设计检查

先读取库存事实再进入发货写事务，锁内重查订单状态、支付状态、库存状态和批次到期日。物流唯一约束与订单行锁共同确保并发只建成一笔物流记录。库存依赖失败且其他请求已提交时可返回原结果。当前为人工物流登记，库存质检与订单记录之间有跨服务时间窗，实物出库授权需由后续 WMS 集成处理。

## 本机启用

1. 按 [M4 指南](m4-payment-close.md)启用订单、支付和库存异步链路。Flyway 为订单服务应用 V5。
2. 复用 M3 的独立 `order-inventory` 密钥对。订单服务配置 `SHOP_ORDER_M5_ENABLED=true` 与 `SHOP_ORDER_INVENTORY_SIGNING_PRIVATE_KEY_LOCATION`。库存服务配置 `SHOP_INVENTORY_RESERVATION_LOOKUP_ENABLED=true` 与 `SHOP_ORDER_INVENTORY_SIGNING_PUBLIC_KEY_LOCATION`。静态发现验证可配置 `SHOP_ORDER_INVENTORY_URL`。
3. 合并并按版本发布 [RBAC 示例](../../../infra/security/examples/rbac-rules.json)中的 `admin-orders-read`、`admin-order-ship`、`order-receipt`。ADMIN 的后台权限使用现有角色权限迁移。
4. 使用管理员调用 `GET /api/v1/admin/orders?status=PAID&size=20`，如有下一页，使用原过滤条件和 `nextCursor`。用订单详情核对 `allocation.shippable`，使用新的幂等键提交发货。买家拿本人令牌及幂等键确认收货。

## 验证记录

- JDK 17 下通过 49 项相关 Maven 测试。涉及 common、user、gateway、product、inventory、order 和 pay，覆盖内部令牌、路由、库存预占、库存批次、购物车、结算、地址、权限和支付回调。日志为 `shop-order/target/m5-isolated-tests.log`。
- `python -X utf8 infra/quality/gate.py lint` 通过。OpenAPI 与 RBAC 示例 JSON 均可解析，四个端点的权限字段和规则匹配。
- 本机隔离环境使用 MySQL 8.4.11、两个 Redis 8.2.10 实例和 RocketMQ 5.5.1。网关与服务用临时端口和静态发现，数据库使用合成数据。复验命令为 `pwsh -NoProfile -File shop-order/target/verify-m5.ps1 -SmokeOnly`，日志为 `shop-order/target/m5-isolated-run.log`。首轮 HTTP 用例达到单 IP 限制，复验仅提高临时实例的通用请求额度。
- 网关复验通过报价、下单、预占、Mock 支付、库存确认、发货到完成收货。后台分页及精确查单、游客和普通用户的后台权限、收货归属、冻结和到期批次拦截均通过。
- 四个并行发货请求只产生一个物流单，复验了同键异内容返回 409。四个并行收货请求得到同一个 `receivedAt`。数据库确认 `COMPLETED`、批次快照保持不变、库存余额不变。
- 模拟分配数量不匹配返回 503。用隔离库检查约束强制幂等绑定失败，验证物流单和订单状态一并回滚；移除测试约束后发货成功。订单日志 `shop-order/target/shop-order-m5.log` 含带 Trace 的 `ORDER_SHIP`、`ORDER_RECEIVE` 审计事件。

本次随订单 API 交付，采用完整 HTTP 演示。冻结和到期可通过后台分配详情与发货拒绝审计查证。验收使用静态服务发现和人工物流登记，生产环境的 WMS 出库授权仍由后续集成提供。
