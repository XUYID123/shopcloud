# ADR-0002 · 消息选型现状

状态为历史记录，基础设施取舍由 [ADR-0004](0004-rocketmq-transport.md)更新。交易类消息的业务协议仍待真实场景决定。现有用例使用同步 HTTP 和数据库缓存失效任务。旧方案 RabbitMQ 为设计留档。真实异步用例到来时，比较同步、DB 任务和 MQ；若需 RocketMQ，按实选版本比较事务消息与 Outbox，评估运维负担、故障与恢复。[设计与验收](../messaging/README.md)
