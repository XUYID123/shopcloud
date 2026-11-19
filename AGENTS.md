# springcloud-demo 工程约束

Java 17 / Spring Boot 4.0.9 / Maven。gateway 为 WebFlux，user/product 使用 MySQL 与 Redis。common/cache 提供共享组件。cart/order/pay 的交易业务当前为骨架。shop-messaging 提供可选 RocketMQ 传输及数据库消费幂等，shop-order 显式启用 Producer。导航见 [Docs](docs/README.md)、[组件目录](docs/architecture/component-catalog.md)、[技能入口](docs/agent-engineering/skills.md)和[工程规则](docs/standards/README.md)。

## 开发前

业务任务遵循[六步开发流程](docs/standards/development-lifecycle.md)。每一步记录可追溯的产物和验收结果，简短任务可使用同一份记录。

1. 阅读适用的 AGENTS.md、相关 Docs 和 ADR，核对工作树、调用方、Controller/Service/Mapper、配置、迁移和现有测试。
2. 业务开发先读取 feature-development。检查可复用的响应、业务异常、日志和 Trace、DTO 校验、身份、缓存和防重组件。仅针对实际变更加用专项技能。
3. 编码前记录简要技术方案，涵盖权限/归属、异常、日志、事务、并发、幂等和失败恢复。高风险或跨域设计先审查；重大未决业务/架构决策先确认。简单 CRUD 可在同一短方案中完成检查。

## 每次评估

- **MySQL**：持久化、事务边界、唯一约束和索引、并发锁或条件更新。
- **Redis**：缓存、短期状态、原子操作或协调的必要性；用到缓存时写明 Key、TTL、失效、并发回填、降级与补偿。
- **RocketMQ**：异步、解耦、削峰、延迟或最终一致性的需求。基础能力已接入，业务消息随用例启用。涉及消息时评估 Topic/Tag/Group、事件 ID 和版本、发送失败、重复、消费幂等、确认/重试/死信、顺序、积压、Broker 故障、DB 一致性、监控和补偿。参阅[消息设计](docs/messaging/README.md)。
- 评估记录采用/复用/无需及依据。保留现有约束；避免无关重构、未获确认的重大依赖和消息协议变更、吞异常、绕过日志及重复基础设施。

## 验证与交付

按 [Definition of Done](docs/standards/definition-of-done.md) 验证正常、异常与适用的权限、并发、回滚和故障。交付记录变更、公共组件、数据库事务、Redis/RocketMQ 结论、异常日志、并发/幂等、测试、残余风险和文档变化。
