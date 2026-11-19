---
name: feature-development
description: 在 springcloud-demo 新增或修改 Java 业务能力时使用，组织需求、方案、实现、验证和交付，按风险加载专项技能。
---

# feature-development

按 [六步生命周期](../../../docs/standards/development-lifecycle.md)推进。小变更可在一份短记录完成。

1. **Requirement Analysis**。读取 [AGENTS.md](../../../AGENTS.md) 和[业务现状](../../../docs/business/README.md)，核对 Controller、调用方、数据及测试。记录需求、已有能力和验收条件。
2. **Technical Design**。列[复用组件](../../../docs/architecture/component-catalog.md)、MySQL/Redis/RocketMQ 逐项取舍、权限、校验、事务和失败恢复。改迁移/SQL/事务时读 [database-design](../database-design/SKILL.md)，改缓存/短期状态时读 [redis-design](../redis-design/SKILL.md)，引入或修改消息时读 [rocketmq-design](../rocketmq-design/SKILL.md)。有争用或幂等时读 [concurrency-design](../concurrency-design/SKILL.md)，有认证、归属或敏感字段时读 [security-review](../security-review/SKILL.md)。
3. **Design Review**。对照 [ADR](../../../docs/decisions/README.md)，核查复用、架构、重复投递、数据丢失、竞争和故障恢复。解决重大未决规则后编码。
4. **Implementation**。完成最小变更，兼容既有契约、调用方和数据。需要可靠副作用时先落实事务与补偿。
5. **Verification**。按[门禁说明](../../../docs/agent-engineering/quality-gate.md)执行适用回归和故障验证，记录证据。执行 [code-review](../code-review/SKILL.md)。
6. **Delivery**。按 [DoD](../../../docs/standards/definition-of-done.md)落盘需求、设计、审查、文件、测试、风险与文档更新。

验收要求每个关键不变量及恢复路径有验证或明确阻塞。依赖增加和业务状态调整须有需求依据。
