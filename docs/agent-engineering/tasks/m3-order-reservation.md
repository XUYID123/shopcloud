# M3 下单与库存预占

2026-11-22，ROUTE-05。交付本人下单、列表和详情，以及库存预占状态、批次分配两条内部查询。库存预占及反悬挂释放走消息。范围见 [M3](../../04-实施与验收.md) 和 [接口及事件契约](../../03-接口文档.md)。

## 方案审查

- MySQL REQUIRED。下单走 checkout 已有报价、地址和商品权威数据，事务外复核。事务先锁用户配额，再按归属锁报价，重查消费标记与过期时间，原子写订单、快照、幂等绑定、配额和 Outbox。同键换报价冲突，换键复用报价冲突。订单列表按创建时间和主键游标分页，两个所有者索引支持可选状态筛选。
- MySQL REQUIRED。库存消费以事务 Inbox 去重，按 orderNo 锁预占记录，然后按 SKU 升序锁汇总，按到期日和批次 ID 锁可售批次。先在同一锁定快照计算全订单分配；任一 SKU 不足时写拒绝和结果事件，批次与汇总均无部分写入。成功时分配、差额、流水和结果事件同事务提交。释放先于预占时创建墓碑；已预占释放按当前日期和质量状态分别回归 available 或 unavailable。
- RocketMQ REQUIRED。沿用 5.x gRPC 普通消息并保留 v1 事件字段。订单写命令 Topic `shop-inventory-commands-v1`，库存组 `shop-inventory-m3-v1` 消费预占/释放 Tag；库存写结果 Topic `shop-order-results-v1`，订单组 `shop-order-m3-v1` 消费成功/拒绝/释放 Tag。双 Topic 可分别下发生产和订阅权限。稳定事件 UUID 随 Outbox 保存；确认不明可重发，消费提交后 ACK。租约到期恢复认领，投递失败指数退避并记录类别，排查走 Outbox 积压和 Broker DLQ。乱序按本地状态与墓碑处理。
- 参数、权限、异常、日志、性能与测试 REQUIRED。用户资源以验签主体归属，网关沿用 `order:create` 与 `order:read:own`。两条库存查询使用订单侧独立密钥及 `order:inventory:read`，默认关闭。复用 Result、Trace 和 BizLog，消息只含 SKU、批次和订单业务编号。校验 Topic/Tag、事件结构、版本及重复内容；隔离验证幂等、FEFO、并发配额、缺货和失败重投。
- Redis NOT_REQUIRED。持久化唯一约束和本地事务负责业务幂等，沿用现有网关权限 Redis。支付关单、确认扣减及履约随后续阶段接入。

## 启用

1. 先按[结算报价](checkout-and-orders.md)启用报价及订单所需的用户、商品签名接口；在订单服务设置 `SHOP_ORDER_M3_ENABLED=true`。订单 Flyway V3 和库存 Flyway V2 新建业务及消息表，用户 Flyway V8 为 ADMIN 补 `order:read:own`。
2. 将[示例规则](../../../infra/security/examples/rbac-rules.json)的 `create-order` 与 `read-own-orders` 合入现有 RBAC 文档，按版本发布。已有用户权限快照按原有 TTL 刷新。
3. 在 RocketMQ 创建方案中的两个普通 Topic 和两个消费组。订单只发布命令、订阅结果，库存只订阅命令、发布结果。设置 `SHOP_ORDER_ROCKETMQ_ENABLED`、`SHOP_INVENTORY_ROCKETMQ_ENABLED` 为 true，并分别配置 `*_ENDPOINTS`。TLS 默认打开，有 ACL 时用各服务的 `*_ACCESS_KEY` 和 `*_SECRET_KEY`。Relay 每轮有界发送，失败退避至约 4 分钟，发生重投时沿用原事件 UUID。运营查看两边的 `rocketmq_outbox` 未发送行、最老积压、`last_error` 和消费组死信队列。
4. 如需启用库存对账接口，用 `java infra/security/GenerateInternalJwtKeys.java infra/local-secrets order-inventory` 生成独立密钥。在库存服务设置 `SHOP_INVENTORY_RESERVATION_LOOKUP_ENABLED=true` 并提供 `SHOP_ORDER_INVENTORY_SIGNING_PUBLIC_KEY_LOCATION`。调用方用 `shop-order` 的私钥为实际 GET 路径签发 `order:inventory:read` 服务 JWT。

预占成功时写回 15 分钟的支付截止时间。到期关单、支付竞争处理、确认扣减及发货由后续交易阶段负责。M3 已支持库存侧释放及乱序墓碑。购物车扣减由独立持久任务完成。

## 验收

- 在隔离 MySQL 8.4.11、Redis 8.2.10 中运行 `mvn -o -pl shop-order,shop-inventory,shop-product,shop-user,shop-gateway -am -Dtest=ReservationIntegrationTests,StockBatchIntegrationTests,CheckoutQuoteServiceTests,CartIntegrationTests,ShopOrderApplicationTests,OrderAddressIntegrationTests,UserPermissionIntegrationTests,SkuIntegrationTests,GatewayRoutingTests,ScopedServiceJwtTests -Dsurefire.failIfNoSpecifiedTests=false test`。公共、用户、网关、商品、库存、订单分别通过 2、10、3、8、11、11 项。
- 库存用例覆盖 FEFO、跳过过期与冻结批次、多 SKU 缺货无部分写、消费事件和业务订单号去重、并发不可超卖、释放墓碑及冻结批次释放，以及写入中断后余额、Inbox 和 Outbox 全部回滚。
- 本地网关、用户、商品、订单、库存和真实 RocketMQ Broker 联调通过五条 HTTP 接口。覆盖并发重复下单、报价过期及改价、本人游标列表、越权、签名绑定的库存查询、购物车数量扣减、缺货失败及配额归还。订单与库存消费日志保留同一 Trace。
- 停止 RocketMQ Proxy 后，对同一用户并发提交 6 笔独立报价订单，恰有 5 笔受理并持久化，余下一笔返回 422。Outbox 记录了发送失败及退避。以同一回环端口重启 Proxy，5 笔订单均转为 UNPAID，抽查预占记录为单份。临时服务、容器和密钥已清理。
- 仓库静态检查及五条 OpenAPI 契约检查通过。端到端使用隔离环境和静态服务发现，容量与生产拓扑沿现有验收计划推进。
