# 业务开发六步流程

以 [feature-development](../../.agents/skills/feature-development/SKILL.md) 和 [AGENTS.md](../../AGENTS.md) 为入口。简单 CRUD 可在一份简短记录中完成六步，按风险补证据。

| 步骤 | 动作与产物 | 进入下一步的条件 |
|---|---|---|
| 1. Requirement Analysis · 需求分析 | 从实际 Controller、调用方、数据和测试界定目标、输入输出、规则、数据变化、同步/异步、异常与一致性。将未知业务规则记入需求记录 | 已确定现有能力、增量、边界和验收条件 |
| 2. Technical Design · 技术设计 | 复用 [组件](../architecture/component-catalog.md)，写 API 契约、权限与归属、输入校验、异常日志、性能与安全。记录下方三个中间件决定、事务、并发和幂等 | 技术方案可对应到源码、迁移、ADR 和可执行测试 |
| 3. Design Review · 方案审查 | 对照 [ADR](../decisions/README.md)核查组件复用、投入收益、架构兼容、丢失、重复、竞争和恢复。重大规则或方向缺证据时确认 | 阻断缺口解决。证据和取舍落盘 |
| 4. Implementation · 实现 | 按分层、既有契约和最小范围编码。兼容历史数据、发布顺序和消息协议；调整必要的迁移与测试 | 已覆盖真实入口、异常及采用方 |
| 5. Verification · 验证 | 按 [质量门禁](../agent-engineering/quality-gate.md)运行适用测试。验证正常/异常、权限、回滚、并发、依赖不可用及恢复。含 MQ 时验证发送失败、重投、消费重试、顺序、死信与 DB 一致性 | 命令、结果、断言与未覆盖的边界已记录，阻断问题已复验 |
| 6. Delivery · 交付 | 通过 [DoD](definition-of-done.md)，记录需求、设计变更、文件、事务、Redis/MQ 取舍、异常日志、并发幂等、测试及剩余风险，更新契约、Docs 和 ADR | 从交付记录可追溯到实际代码和验证结果 |

## 技术设计的固定决策

- **MySQL**：记录是否持久化，表、索引、事务、锁和持久幂等。涉及迁移或 SQL 时使用 [database-design](../../.agents/skills/database-design/SKILL.md)。
- **Redis**：记录必要性、Key、TTL、失效、并发回填、故障与补偿。变更缓存时使用 [redis-design](../../.agents/skills/redis-design/SKILL.md)。
- **RocketMQ**：记录异步必要性，Topic、Tag、契约 ID/版本、发送方式、消费者、事务投递、幂等、重试、死信、顺序、积压、监控、补偿。涉及消息时使用 [rocketmq-design](../../.agents/skills/rocketmq-design/SKILL.md)。
- 按风险使用 [concurrency-design](../../.agents/skills/concurrency-design/SKILL.md)、[security-review](../../.agents/skills/security-review/SKILL.md)，交付前使用 [code-review](../../.agents/skills/code-review/SKILL.md)。
