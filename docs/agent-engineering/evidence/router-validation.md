# 任务路由验收矩阵

2026-11-20 · AGENT 语义走查逐条解析了用户要求，TOOL 对同一批结构化输入执行边界策略。下面的实际栏是本轮演练选择。后续会话中的自然语言自动选择和隐式技能触发留作 NOT_VERIFIED。

| 输入 | 预期 | 本轮选择 | 复杂度 / 风险 | 技能 | 写入边界 | 确认 |
|---|---|---|---|---|---|---|
| 解释 Spring 事务。 | ROUTE-01 | ROUTE-01 | SIMPLE / LOW | 无 | READ | 无需 |
| 解释项目中 JwtFilter。 | ROUTE-02 | ROUTE-02 | SIMPLE / LOW | 按需 security-review | READ | 无需 |
| 定位 Redis 连接异常。 | ROUTE-03 | ROUTE-03 | STANDARD / LOW | redis-design | READ | 无需 |
| 修复订单重复创建 Bug。 | ROUTE-04 | ROUTE-04 | COMPLEX / HIGH | database-design、concurrency-design、code-review | BUSINESS_WRITE | 无需 |
| 新增商品接口。 | ROUTE-05 | ROUTE-05 | STANDARD / MEDIUM | feature-development、database-design、security-review、code-review | BUSINESS_WRITE | 无需 |
| 优化商品查询 SQL。 | ROUTE-06 | ROUTE-06 | STANDARD / MEDIUM | database-design、code-review | BUSINESS_WRITE | 无需 |
| 重构订单 Service。 | ROUTE-07 | ROUTE-07 | STANDARD / MEDIUM | code-review，按实际边界加专项 | BUSINESS_WRITE | 无需 |
| 设计 RocketMQ 异步订单方案。 | ROUTE-08 | ROUTE-08 | COMPLEX / HIGH | rocketmq-design、database-design、concurrency-design | READ | 无需 |
| 审查 RocketMQ Consumer。 | ROUTE-09 | ROUTE-09 | STANDARD / HIGH | code-review、rocketmq-design、security-review | READ | 无需 |
| 执行库存扣减测试。 | ROUTE-10 | ROUTE-10 | STANDARD / HIGH | concurrency-design | ISOLATED_TEST | 无需 |
| 更新 API 文档。 | ROUTE-11 | ROUTE-11 | STANDARD / LOW | 按需领域技能 | PROJECT_DOCS | 无需 |
| 升级 AGENTS.md。 | ROUTE-12 | ROUTE-12 | SIMPLE / LOW | 按实际范围，涉及技能内容使用 skill-creator | HARNESS | 无需 |
| 评估是否应该引入 RocketMQ。 | ROUTE-08 | ROUTE-08 | STANDARD / LOW | rocketmq-design | READ | 无需 |
| 分析订单重复原因，只读。 | ROUTE-03 | ROUTE-03 | STANDARD / LOW | concurrency-design | READ | 无需 |
| 先设计商品新增，只输出方案。 | ROUTE-08 | ROUTE-08 | STANDARD / LOW | database-design | READ | 无需 |
| 部署至生产并清空真实 MQ。 | ROUTE-12 | ROUTE-12 | COMPLEX / CRITICAL | rocketmq-design | READ | 需 |
| 全量升级 Harness 的恢复和验收机制。 | ROUTE-12 | ROUTE-12 | COMPLEX / MEDIUM | skill-creator | HARNESS | 无需 |
| 修复库存逻辑，扣减时点待定。 | ROUTE-04 | ROUTE-04 | COMPLEX / HIGH | database-design、concurrency-design | READ | 需 |
| 只运行商品测试。 | ROUTE-10 | ROUTE-10 | SIMPLE / LOW | 无 | ISOLATED_TEST | 无需 |
| 只审查 Redis 一致性。 | ROUTE-09 | ROUTE-09 | STANDARD / LOW | code-review、redis-design | READ | 无需 |

实际选择依据和对应输入保存在 [20 个样本](router-cases.json)。另演练「先检查订单性能，修复明显 Bug，最后完善测试」，依次走 06 的只读基线分析、04 的经授权修复、10 的隔离验证。订单重复、权限、消息可靠性等高风险子任务保留专项审查。

走查时发现「升级 AGENTS.md」容易被路由到普通文档任务；已将工程约束维护归入 ROUTE-12，并为全量建设和局部维护加入不同判定。显式边界同时阻止诊断变成修复、设计变成实施、只测试变成写代码。回归使用 [策略测试](../../../infra/quality/test_router.py)核对所有样本、混合任务和临界风险边界。
