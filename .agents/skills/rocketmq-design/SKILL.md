---
name: rocketmq-design
description: 在评估 RocketMQ、实现生产消费或修改消息契约、可靠性与故障恢复时使用。
---

# rocketmq-design

## 工作流程

1. 从 [RocketMQ 知识页](../../../docs/messaging/README.md) 和 [消息 ADR](../../../docs/decisions/0002-messaging.md) 核对接入状态。先比较同步、数据库任务和 MQ。需 MQ 时确认客户端/Broker 版本、NameServer、HA、认证/ACL 和拓扑，再选版本对应 API。
2. 列 Producer/Consumer、Topic/Tag/Group、业务 Key、eventId、schemaVersion、traceId、序列化兼容、顺序范围及分片键。记录同步、异步或单向发送选择，超时、回调、失败状态和事件 ID 重用。
3. 依据 [DB 一致性](../../../docs/messaging/database-consistency.md) 比较事务消息与 Outbox。对认领租约、回查、发送不确定、ACK 时机、重复/并发事务和幂等唯一约束逐步推演。
4. 定义消费失败分类、退避和限次、DLQ 告警与受控重放、消费者重启和 Broker 故障恢复。以积压年龄、失败率、消费负载与扩容阈值设计监控，审查顺序与吞吐之间的取舍。
5. 用隔离实例测试发送、发送失败、重复/并发、消费失败、重试、死信、进程重启、Broker 不可用、乱序、积压和补偿；检查业务写及幂等记录、追踪、ACK。

## 输出与验收检查

输出：选型、契约、生产/消费时序、故障矩阵、监控、重放与测试证据。禁止把当前缓存 outbox 当作 MQ 封装、把可靠消息设置为单向或在业务提交前 ACK。验收：每个失败明确状态、重试负责方、幂等边界与恢复验证。
