# RocketMQ 基础接入

[shop-messaging](../../shop-messaging/pom.xml)使用 Apache RocketMQ Java SDK 5.2.2。服务使用 Proxy gRPC endpoint；NameServer、Broker 和管理端口留在基础设施网络。完整门禁会创建独立消息 Topic、消费组和 MySQL 测试 schema。

## 服务配置

[shop-order 装配](../../shop-order/src/main/java/org/spring/shoporder/messaging/OrderRocketMqConfiguration.java)默认关闭。启用后设置 `SHOP_ORDER_ROCKETMQ_ENDPOINTS`，M3 命令 Topic 默认 `shop-inventory-commands-v1`。TLS 默认开启，访问密钥使用环境注入 `SHOP_ORDER_ROCKETMQ_ACCESS_KEY` 与 `SHOP_ORDER_ROCKETMQ_SECRET_KEY`。开启凭据时要求 TLS。连接由后台任务按需建立，生产者与消费者随应用关闭。测试使用隔离网络和本机回环 Proxy。[M3 的 Topic 和消费组](../agent-engineering/tasks/m3-order-reservation.md)

## 消息与事务

- [RocketMqTransport](../../shop-messaging/src/main/java/org/spring/shopmessaging/RocketMqTransport.java)从显式 Topic/Tag 同步发送，返回 Broker 消息 ID。稳定 UUID 作为消息 Key，也随协议版本和可选 traceId 写入属性。技术传输使用普通消息，顺序见各业务契约。
- 生产者重试须使用相同事件 ID。可靠的业务提交依照[事务 Outbox](database-consistency.md)，由采用服务在同事务登记并持久化重试进度。
- 消费者验证消息头后调用 handler。先把 [inbox DDL](../../shop-messaging/src/main/resources/sql/rocketmq-inbox.sql)转为采用服务的 Flyway 迁移，再通过 [JdbcMessageInbox](../../shop-messaging/src/main/java/org/spring/shopmessaging/JdbcMessageInbox.java)把收据和同数据源的业务写放在一个事务。以消费组和事件 ID 去重；禁止为相同 ID 重新生成不同事件。正常返回表示提交完成，异常交给 Broker 重投。外部副作用单独做幂等或补偿。
- Broker 重试次数由消费组配置。运行告警关注发送超时、消费失败、消费时延、积压年龄与死信；重放沿用原事件 ID。日志关联组、消息、事件、Trace 和错误类别，正文与凭据不进入日志。
- M3 已接入预占命令、库存结果与释放墓碑，事务投递复用 `JdbcMessageOutbox`。消息正文上限 1 MiB，以覆盖 20 种 SKU 最多 2,000 条批次分配。[完整门禁](../agent-engineering/quality-gate.md)运行基础设施验收。
- M6 的独立 Outbox 投递工作线程复用原有行锁、租约和 Inbox 事务。投递日志记录重试次数，消费失败日志记录 Broker 投递次数。从已查证的源 Outbox 重放时保留原事件 ID。[巡检与死信处理](../../infra/stability/README.md#broker-重试与安全恢复)
