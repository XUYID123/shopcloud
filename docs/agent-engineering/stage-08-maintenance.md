# 阶段 8 维护机制验收

[维护规范与版本记录](maintenance.md)定义复盘、变更位置、更新 ADR、技能复验和回归规则。

## 重复消费遗漏演练

**输入**为订单异步代码审查发现，方案使用 Broker 消息 ID 去重。发送回执丢失后的重投会分配另一个 Broker ID，同时保留应用 eventId，业务写入因此可能发生两次。缺口属于技术设计和方案审查，若进入实现则可能导致重复业务副作用。

1. **根因定位**。回溯主技能到 [RocketMQ 技能](../../.agents/skills/rocketmq-design/SKILL.md)、[消息知识库](../messaging/README.md)、[DB 与消息一致性](../messaging/database-consistency.md)和 [ADR-0004](../decisions/0004-rocketmq-transport.md)。既有规范已经要求稳定事件 ID、消费组级唯一键和提交后 ACK。模拟根因为未读取专项要求并把 Broker 投递标识当成应用事件标识。
2. **修复设计**。Outbox 用稳定业务 eventId。以 group + eventId 唯一键在同一事务插入收据和业务写。消费重复时返回成功并保留原结果。消费失败时回滚两者，让 Broker 重投。外部调用使用单独持久任务和下游幂等 ID。
3. **回归**。[RocketMqIntegrationTests](../../shop-messaging/src/test/java/org/spring/shopmessaging/RocketMqIntegrationTests.java)模拟两个不同 Broker ID 携带同一 eventId，并断言一次副作用。另一用例注入第一次写入失败，验证回滚、Broker 重投和最终一次提交。改用 Broker ID 的模拟修复会违反第一个断言。阶段 5 已真实执行两项回归。
4. **维护落点**。设计未执行规范时在同一变更修方案和消费代码；若专项描述无法触达，改主技能路由；若 API 或语义变化，更新 Docs 与 ADR，复验后记录版本。只有可普遍复用的规则才进入全局约束。

## 自检

缺口、原因、应改位置、架构决策、版本和可执行回归均有对应记录。场景仅演练审查和维护流程。既有业务代码及回归文件摘要与阶段 5 一致。
