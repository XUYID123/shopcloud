# ADR-0004 · RocketMQ 5.x 基础接入

状态为已实现基础传输，替代 [ADR-0002](0002-messaging.md)中的中间件未接入状态。业务事件仍按用例设计。

选择 Apache 5.x gRPC SDK、显式主题与订阅、同事务消费去重和提交后 ACK。与同步 HTTP 或 DB 调度相比，消息便于独立重试、解耦和削峰，同时引入部署、观察和运维成本。保留现有 HTTP 与 Redis 失效队列。订单服务启用后建立并关闭 Producer，消费规则和可靠生产事务由采用方实现。

发生重投时保留 eventId；同组同 ID 只执行一次数据库写。可恢复业务故障回滚并返回消费失败，由 Broker 按情况重试。部署采用环境凭据、TLS、最小 ACL 和私有网络；正常消息的顺序与后续 FIFO 设计分别由主题约定。参见[技术接入](../messaging/rocketmq-integration.md)和[数据库与消息一致性](../messaging/database-consistency.md)。
