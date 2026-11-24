---
name: feature-development
description: 在 springcloud-demo 的 ROUTE-05 新增或修改 Java 业务能力时使用，组织需求、方案、实现、验证和交付，按风险加载专项技能。
---

# feature-development

先按[任务路由](../../../docs/agent-engineering/workflows/task-routing.md)确认 ROUTE-05 和操作授权。按[工作流 B](../../../docs/standards/development-lifecycle.md)推进。短任务共用一份记录，复杂任务使用[独立任务记录](../../../docs/agent-engineering/tasks/README.md)。

1. **Requirement Analysis**。读取根和局部 AGENTS、[业务现状](../../../docs/business/README.md)、ADR，找真实入口、调用方、数据及测试，记录增量、边界和验收条件。
2. **Technical Design**。核查[复用组件](../../../docs/architecture/component-catalog.md)。对 MySQL、Redis、RocketMQ、异常、日志、参数、权限与安全、性能和测试逐项记录 REQUIRED、NOT_REQUIRED 或 NEEDS_CONFIRMATION 及原因。按影响加载 [database-design](../database-design/SKILL.md)、[redis-design](../redis-design/SKILL.md)、[rocketmq-design](../rocketmq-design/SKILL.md)、[concurrency-design](../concurrency-design/SKILL.md)或 [security-review](../security-review/SKILL.md)。
3. **Design Review**。对照 [ADR](../../../docs/decisions/README.md)，检查复用、架构、丢失、重复、竞争和故障恢复。未决关键规则或额外权限先确认。
4. **Implementation**。完成授权范围内的最小修改，兼容既有契约、调用方及数据。
5. **Verification**。按[门禁](../../../docs/agent-engineering/quality-gate.md)验证正常、异常、并发和故障场景，运行 [code-review](../code-review/SKILL.md)。保留命令、结果和范围。
6. **Delivery**。按 [DoD](../../../docs/standards/definition-of-done.md)落盘变更、取舍、证据、风险和恢复位置。
