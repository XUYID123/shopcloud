# Agent Task Router

收到每条消息后先确定它是在开启新任务、改变当前任务的范围，还是询问当前任务进度。延续的任务先合并新约束，保留已完成的工作与证据。按实际目标和授权选择主路由，技术名词只影响专项技能。例如，解释 Redis 一致性、审查项目缓存、修复一致性缺陷、设计缓存架构分别进入 01、09、04、08。

## 决策顺序

1. 记录用户期望的结果、明确边界和授权，显式的只分析、只审查、先设计、只运行测试优先。理解当前代码、谈话上下文与操作目的。
2. 选择下表一条主路由。混合请求按依赖拆成多个子任务，各自保留授权。例如先测量或分析性能，再修复获授权的明确缺陷，最后跑测试。已有项目常规维护从 ROUTE-12 的轻量分支进入。
3. 标记复杂度。SIMPLE 只做必要阅读和最少验证。STANDARD 执行主路由必要步骤。COMPLEX 写清边界、方案、审查与验证。标记风险。LOW 为只读或局部文档，MEDIUM 为普通代码和配置，HIGH 为一致性、库存、权限、迁移、可靠消息或重要依赖，CRITICAL 为生产、破坏性操作、正式部署或核心架构变更。
4. 选择必要的专业技能。高风险增加审查、隔离验证及失败覆盖。关键业务规则未决或操作超出授权时先确认。只读范围允许必要的安全检查，写文件以用户授权为准。设计任务的 ADR 草案可在聊天交付；用户授权落盘时写入项目。
5. 保留命令、结果和限制，复杂任务用[独立记录](../tasks/README.md)。正常推进可自主完成。只提交用户要求的产物。

[边界策略](../../../infra/quality/router_policy.py)接收 Agent 已解释的意图、风险和授权，用于检查路由选择。自然语言理解由 Agent 完成。[路由验收](../evidence/router-validation.md)

## 主路由

| 路由 | 进入条件 | 最小流程与交付 |
|---|---|---|
| ROUTE-01 Knowledge QA | 概念、原理或选型对比 | 判断是否涉及本仓库，必要时读相关上下文，给出聚焦解释和示例 |
| ROUTE-02 Code Explanation | 指定项目类、方法或 SQL | 读实际位置与直接依赖，解释输入、处理、输出和调用关系 |
| ROUTE-03 Bug Diagnosis | 定位故障或解释报错 | 读日志、配置和代码；验证可证假设，区分根因与推测，给出修复方向 |
| ROUTE-04 Bug Fix | 明确授权修复 | 复现、定位根因、最小修复、针对性回归、检查副作用。保持既有校验和有效测试 |
| ROUTE-05 Feature Development | 新增或变更业务能力 | 调用 [feature-development](../../../.agents/skills/feature-development/SKILL.md) 与[六步协议](../../standards/development-lifecycle.md)，按需加载专业技能 |
| ROUTE-06 Performance Optimization | 优化已知性能目标 | 先测基线与瓶颈，再按授权优化，复测性能和业务结果。缺少可靠测量时保留未验证范围 |
| ROUTE-07 Refactoring | 调整结构并保持对外行为 | 确认契约，建立行为保护，分步调整，回归 API、数据、事务、异常、缓存及消息兼容 |
| ROUTE-08 Architecture Design | 设计架构或研究方案取舍 | 检查约束，比较候选、质量属性、代价、风险及迁移；必要时起草 ADR |
| ROUTE-09 Code Review | 审查代码、差异或实现 | 使用 [code-review](../../../.agents/skills/code-review/SKILL.md)，按严重度报告位置、原因和验证方法 |
| ROUTE-10 Testing & Verification | 执行测试或启动验证 | 确认隔离环境和范围，执行适用测试，报告命令、失败、覆盖与限制；明确授权修复后切到 04 |
| ROUTE-11 Documentation | 编写或更新文档 | 对照真实代码与已有文档，复用权威入口，检查事实和链接，交付变更摘要 |
| ROUTE-12 Harness Maintenance | 修改规范、Skill、CI 或路由 | 审计影响范围，修复权威规则，检查冲突、发现机制和门禁，保存证据并更新维护记录。首次建设与全量升级使用[八阶段建设](../construction-workflow.md) |

Redis 缓存与故障使用 redis-design，MySQL 表/迁移/事务使用 database-design，消息可靠性用 rocketmq-design，竞争/幂等用 concurrency-design，身份/权限/信任边界用 security-review。以[技能索引](../skills.md)的范围为准。
