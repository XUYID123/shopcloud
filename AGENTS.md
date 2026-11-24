# springcloud-demo 工程约束

Java 17 / Spring Boot 4.0.9 / Maven。gateway 为 WebFlux，user/product 使用 MySQL 与 Redis。common/cache 提供共享组件。cart/order/pay 的交易业务当前为骨架。shop-messaging 提供可选 RocketMQ 传输及数据库消费幂等，shop-order 显式启用 Producer。导航见 [Docs](docs/README.md)、[组件目录](docs/architecture/component-catalog.md)、[技能入口](docs/agent-engineering/skills.md)和[工程规则](docs/standards/README.md)。

## 新会话初始化

1. 先读取本文件，进入目标路径前阅读所有适用的局部 AGENTS.md。核对当前工作目录、分支、工作树和未提交改动。
2. 阅读[项目总览](README.md)、[项目状态](PROJECT_STATUS.md)，检查[会话交接](SESSION_HANDOFF.md)是否有未完成事项。交接缺失、为空或与请求无关时，从当前用户请求开展。
3. 交接任务与当前请求相关时，核对对应代码、最后通过的测试、待验证项和阻塞条件。仍有未完成工作时，从已核实的恢复位置继续，遵守当前授权和记录中的审核边界。
4. 按[任务路由](docs/agent-engineering/workflows/task-routing.md)选择工作流，再读取相关 Docs、ADR 和所需 [Skills](docs/agent-engineering/skills.md)。源码、配置、迁移及实际测试结果优先，缺少证据的项目事实标记为待确认。

## 开发前

每条消息先按[任务路由](docs/agent-engineering/workflows/task-routing.md)识别意图、授权、复杂度和风险，再选择主流程。明确的只读、设计、审查和测试要求决定操作边界，混合任务按依赖拆分。首次建设或全量升级使用[八阶段建设与恢复](docs/agent-engineering/construction-workflow.md)。

功能开发走 ROUTE-05，遵循[六步开发流程](docs/standards/development-lifecycle.md)。复杂任务将产物、验收和恢复位置保存到[独立任务记录](docs/agent-engineering/tasks/README.md)。简短任务在同一份交付中记录。

1. 阅读适用的 AGENTS.md、相关 Docs 和 ADR，核对工作树、调用方、Controller/Service/Mapper、配置、迁移和现有测试。
2. 功能开发读取 feature-development。其他任务按路由加载专项技能。修改业务能力时检查可复用的响应、业务异常、日志和 Trace、DTO 校验、身份、缓存和防重组件。
3. 编码前记录简要技术方案，涵盖权限/归属、异常、日志、事务、并发、幂等和失败恢复。高风险或跨域设计先审查；重大未决业务/架构决策先确认。简单 CRUD 可在同一短方案中完成检查。

## 业务设计评估

- **MySQL**：持久化、事务边界、唯一约束和索引、并发锁或条件更新。
- **Redis**：缓存、短期状态、原子操作或协调的必要性；用到缓存时写明 Key、TTL、失效、并发回填、降级与补偿。
- **RocketMQ**：异步、解耦、削峰、延迟或最终一致性的需求。基础能力已接入，业务消息随用例启用。涉及消息时评估 Topic/Tag/Group、事件 ID 和版本、发送失败、重复、消费幂等、确认/重试/死信、顺序、积压、Broker 故障、DB 一致性、监控和补偿。参阅[消息设计](docs/messaging/README.md)。
- 对照六步协议写出 REQUIRED、NOT_REQUIRED 或 NEEDS_CONFIRMATION 及理由。保留现有约束，避免无关重构、未获确认的重大依赖和消息协议变更、吞异常、绕过日志及重复基础设施。

## 验证与交付

按 [Definition of Done](docs/standards/definition-of-done.md) 验证正常、异常与适用的权限、并发、回滚和故障。交付记录变更、公共组件、数据库事务、Redis/RocketMQ 结论、异常日志、并发/幂等、测试、残余风险和文档变化。

交付前按[文档维护规则](docs/agent-engineering/maintenance.md#项目上下文维护)复核代码、配置、验证结果与项目文档。架构、进度或规则有变化时更新各自入口。会话结束时有重要未完成工作需要交接，才更新交接摘要，并链接现有任务记录。

如需覆盖重要业务数据、运行破坏性迁移、操作生产或清空真实队列、部署、升级重大依赖、改变核心架构或未决业务规则，先依据操作范围取得确认及工具授权。
