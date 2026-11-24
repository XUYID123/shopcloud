# M4 支付与关闭

2026-11-23，ROUTE-05。范围为支付单、Mock 回调及事件、取消申请、订单支付上下文和支付对账接口，以及相应确认扣减、关单释放和补偿。沿用现有 `shop-pay` 模块。

## 方案审查

- MySQL REQUIRED。支付库按订单号锁控制行，串行化 Mock 创建、收款事实和关单。Mock 渠道事实持久化，关单查证先使用该事实，未创建也保存关闭墓碑。调用订单核验放在写事务外，锁内再检查墓碑与过期时间。支付单、请求幂等绑定、原始回调摘要、nonce 与支付流水均用唯一键保护。回调落账与 Outbox 同事务；Mock 回调任务失败可重投，使用重新签发的请求头且正文保持不变。Mock 全额退款通过独立退款编号在支付库幂等结算。
- MySQL REQUIRED。取消和超时沿订单行锁推进，订单、申请、配额和事件原子更新。支付成功与关单结果只允许相应状态迁移；已确认关闭后的收款进入全额退款，即使支付结果先于关闭结果送达。库存确认与释放由同一预占记录串行化，确认只移动已预占余额。无预占、已拒绝或已释放的确认返回操作拒绝，订单进入异常退款流程。
- RocketMQ REQUIRED。复用 M3 的库存 Topic、Inbox 和 Outbox。另用 `shop-payment-commands-v1` 和 `shop-payment-results-v1` 传输关单、退款及支付结果，各自的消费组按 Topic 授权。使用原有 v1 消息信封、业务 Tag 和同一事件 ID 重投。支付成功结果附带本地关单是否已确认的标记，用于纠正乱序；支付服务仍是支付事实的权威。DLQ 及积压以现有 Outbox 和消费组监控。
- 参数、权限、日志、性能和验证 REQUIRED。用户读取按验签主体所有权；Mock 事件仅 dev/test ADMIN，生产 Profile 排除。回调只允许 MOCK、原始字节 HMAC、五分钟时间窗及 nonce 绑定。两条内部查询均使用独立服务 JWT。日志沿用 Trace，排除密钥、签名与回调正文。隔离验证正常支付、取消、超时、竞争、重复、库存拒绝及退款。
- Redis NOT_REQUIRED。复用网关已有权限缓存，交易一致性使用各服务 MySQL。真实渠道及物流履约沿后续计划。

## 启用

1. 先按 [M3](m3-order-reservation.md)启用订单、商品、用户、库存及 RocketMQ。运行 `docker compose -f shop-pay/compose.yaml up -d` 启动开发支付库，默认端口 3311。Flyway 为支付执行 V1、订单执行 V4、库存执行 V3、用户执行 V9。
2. 用 `java infra/security/GenerateInternalJwtKeys.java infra/local-secrets pay-order` 和 `java infra/security/GenerateInternalJwtKeys.java infra/local-secrets order-pay` 生成独立密钥。支付持有 pay-order 私钥，订单持有其公钥；库存及支付对账各自使用对应签发者的公钥。默认文件名在模块 `application.properties` 中定义。
3. 订单设置 `SHOP_ORDER_M4_ENABLED=true`；支付设置 `SHOP_PAY_ENABLED=true`、`SHOP_PAY_ROCKETMQ_ENABLED=true`、`SHOP_PAY_ROCKETMQ_ENDPOINTS` 及至少 32 字节的独立 `SHOP_PAY_MOCK_SECRET`。商户默认 `flour-dev`。仅 `dev` 或 `test` Profile 启用管理端 Mock 事件，`prod` 和 `production` 优先排除。订单上下文默认由 Nacos 发现，本机可以用 `SHOP_PAY_ORDER_URL`。
4. 创建普通 Topic `shop-payment-commands-v1`、`shop-payment-results-v1` 及消费组 `shop-pay-m4-v1`、`shop-order-pay-m4-v1`。新增 Tag 和 v1 字段见[接口与事件文档](../../03-接口文档.md)。按版本合并并发布 [RBAC 规则](../../../infra/security/examples/rbac-rules.json)中的 `payment-*`、`order-cancel`、`order-cancellation-read`。

Mock 创建、查单、关单和退款由本地数据库事务确定结果，外部渠道的网络未知态由未来适配器负责。回调经签名校验，业务提交后才 ACK。可监控 `mock_provider_event` 的未投递行，以及订单、支付、库存三侧 Outbox 的最老待发记录和消费组死信。

## 验收

- 在隔离 MySQL 8.4.11 与 Redis 8.2.10 中执行 Maven 选择性回归，公共、用户、网关、商品、库存、订单与支付分别通过 2、10、3、8、12、11、3 项，共 49 项。新增回调测试覆盖原始字节签名、商户、时间窗及长度。库存测试验证重复确认只移动一次余额，确认后释放不会回补。
- 通过真实网关、订单、支付、库存、商品、用户和 RocketMQ 验证 9 条 HTTP 接口。并发支付只创建一张支付单，报价应付 7,400 分。真实 Mock 回调使订单进入 PAID/CONFIRMED。普通用户访问 Mock 入口、混合 dev/production Profile 发起模拟支付、越权读支付及取消、未验签内部查询均被拒绝。
- 联调还验证了无支付单的关单墓碑、超时自动关闭、先关单再释放、相同回调确认与篡改拒绝、nonce 重用及金额不符、关闭后晚到付款全额退款。暂停并恢复 RocketMQ Proxy 时，已经持久化的收款事实赢得取消竞争。库存确认被拒绝时，订单进入库存异常并只记录一笔全额退款。
- 静态和 OpenAPI 检查通过。联调采用隔离环境与静态服务发现，临时进程、容器及密钥已清理。
