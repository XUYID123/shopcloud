# Java Agent Engineering Harness 最终验收

验收日期 **2026-11-19**。八阶段全部通过。从[建设检查点](checkpoint.md)可找到每阶段的产物、证据和状态。

## 阶段结果

| 阶段 | 状态与证据 |
|---|---|
| 1 项目审计 | PASS · [初始审计](stage-01-audit.md) |
| 2 项目知识库 | PASS · [知识库验收](stage-02-knowledge-base.md) |
| 3 全局约束 | PASS · [AGENTS 验收](stage-03-agents.md) |
| 4 专业 Skills | PASS · [技能发现和调用演练](stage-04-skills.md) |
| 5 自动化门禁 | PASS · [构建、依赖与真实 RocketMQ 验证](stage-05-quality.md) |
| 6 开发生命周期 | PASS · [六步流程验收](stage-06-lifecycle.md) |
| 7 实战验收 | PASS · [四场景 103/108（95.4/100）](stage-07-scenarios.md) |
| 8 持续维护 | PASS · [版本、ADR 和回归演练](stage-08-maintenance.md) |

## 文件与变更清单

| 位置 | 新增与更新 |
|---|---|
| 仓库根目录 | [原任务书](../../Java%20Agent%20Engineering%20Harness%20建设任务书.md)、[AGENTS.md](../../AGENTS.md)、[.gitignore](../../.gitignore)、[.gitleaks.toml](../../.gitleaks.toml) |
| `.agents/skills/` | feature-development、database-design、redis-design、rocketmq-design、concurrency-design、security-review、code-review。详见[技能入口](skills.md) |
| `docs/` | 更新[导航](../README.md)，建立[架构](../architecture/README.md)、[组件目录](../architecture/component-catalog.md)、[规则](../standards/README.md)、[DoD](../standards/definition-of-done.md)、[六步流程](../standards/development-lifecycle.md)、[数据库](../database/README.md)、[Redis](../middleware/README.md)、[消息](../messaging/README.md)、[消息一致性](../messaging/database-consistency.md)、[接入说明](../messaging/rocketmq-integration.md)、[安全](../security/README.md)、[业务](../business/README.md)、[ADR 索引与 0001～0004](../decisions/README.md) |
| `docs/agent-engineering/` | 八份阶段验收，外加[检查点](checkpoint.md)、[技能索引](skills.md)、[门禁说明](quality-gate.md)、[恢复方案](stage-05-resume-design.md)、[依赖扫描记录](stage-05-dependency-findings.md)、[规范维护与变更记录](maintenance.md)、[初始源码摘要](source-baseline.sha256)及本报告 |
| `infra/quality/` | [门禁脚本](../../infra/quality/gate.py)、[隔离 Compose](../../infra/quality/compose.yaml)、[Broker 配置](../../infra/quality/rocketmq-broker.conf)、[Proxy 配置](../../infra/quality/rocketmq-proxy.json) |
| Git / CI | [.githooks/pre-commit](../../.githooks/pre-commit)、[.github/workflows/quality.yml](../../.github/workflows/quality.yml) |
| 应用配置 | 更新 `shop-gateway/src/main/resources/application.yml`、`shop-gateway/examples/application-governance.yml`、`shop-user/src/main/resources/application.yml`、`shop-product/src/main/resources/application.properties`、`shop-cart/src/main/resources/application.properties`、`shop-order/src/main/resources/application.properties`、`shop-pay/src/main/resources/application.properties`。新增 `shop-user/src/test/resources/application-harness.properties` |

以下是对阶段 1 摘要逐文件复查得到的 Java、SQL 和 POM 清单。`+` 表示新增，`~` 表示修改。

```text
~ pom.xml
~ shop-base-parent/pom.xml
~ shop-cache/pom.xml
~ shop-cache/src/main/java/org/spring/shopcache/SafeCacheAutoConfiguration.java
~ shop-cache/src/test/java/org/spring/shopcache/SafeCacheIntegrationTests.java
~ shop-cart/pom.xml
~ shop-common/pom.xml
~ shop-common/src/main/java/org/spring/shopcommon/idempotent/IdempotentAutoConfiguration.java
~ shop-common/src/test/java/org/spring/shopcommon/idempotent/IdempotentAutoConfigurationTests.java
~ shop-gateway/pom.xml
~ shop-gateway/src/main/java/org/spring/shopgateway/config/GatewayRedisConfiguration.java
~ shop-gateway/src/main/java/org/spring/shopgateway/error/GatewayErrorWebExceptionHandler.java
~ shop-gateway/src/main/java/org/spring/shopgateway/ShopGatewayApplication.java
~ shop-gateway/src/main/java/org/spring/shopgateway/web/GatewayRequestFilter.java
+ shop-gateway/src/main/java/org/spring/shopgateway/web/TrustedClientIpHeadersFilter.java
~ shop-gateway/src/test/java/org/spring/shopgateway/auth/AuthenticationAuthorizationFilterTests.java
~ shop-gateway/src/test/java/org/spring/shopgateway/error/GatewayErrorWebExceptionHandlerTests.java
~ shop-gateway/src/test/java/org/spring/shopgateway/GatewayAntiAbuseTests.java
~ shop-gateway/src/test/java/org/spring/shopgateway/GatewayAuthenticationTests.java
~ shop-gateway/src/test/java/org/spring/shopgateway/GatewayRequestFilterTests.java
+ shop-messaging/pom.xml
+ shop-messaging/src/main/java/org/spring/shopmessaging/JdbcMessageInbox.java
+ shop-messaging/src/main/java/org/spring/shopmessaging/MessageEvent.java
+ shop-messaging/src/main/java/org/spring/shopmessaging/RocketMqTransport.java
+ shop-messaging/src/main/resources/sql/rocketmq-inbox.sql
+ shop-messaging/src/test/java/org/spring/shopmessaging/MessageEventTests.java
+ shop-messaging/src/test/java/org/spring/shopmessaging/RocketMqIntegrationTests.java
~ shop-order/pom.xml
+ shop-order/src/main/java/org/spring/shoporder/messaging/OrderRocketMqConfiguration.java
~ shop-pay/pom.xml
~ shop-product/pom.xml
~ shop-product/src/main/java/org/spring/shopproduct/web/ProductJsonConfiguration.java
~ shop-product/src/test/java/org/spring/shopproduct/CategoryIntegrationTests.java
~ shop-product/src/test/java/org/spring/shopproduct/ProductIntegrationTests.java
~ shop-product/src/test/java/org/spring/shopproduct/ProductListIntegrationTests.java
~ shop-product/src/test/java/org/spring/shopproduct/ProductQueryIntegrationTests.java
~ shop-product/src/test/java/org/spring/shopproduct/ProductSaleIntegrationTests.java
~ shop-product/src/test/java/org/spring/shopproduct/SkuIntegrationTests.java
~ shop-user/pom.xml
~ shop-user/src/main/java/org/spring/shopuser/auth/AuthRedisConfiguration.java
~ shop-user/src/main/java/org/spring/shopuser/config/RegistrationConfiguration.java
~ shop-user/src/test/java/org/spring/shopuser/AuthIntegrationTests.java
~ shop-user/src/test/java/org/spring/shopuser/UserPermissionIntegrationTests.java
~ shop-user/src/test/java/org/spring/shopuser/UserRegistrationIntegrationTests.java
```

## 场景与工程验收

- A 新增商品核对已实现 POST 的验证、授予权限、分类约束、日志和无新增中间件。B 修改商品演练版本条件更新与同事务缓存失效。C 库存扣减演练库存下界、持久幂等、并发、事务回滚。D 订单异步演练事件 Outbox、稳定 ID、消费幂等、退避、死信、补偿和监控。[逐项评分和证据](stage-07-scenarios.md)
- 消费失败及重试、重复消息消费通过真实隔离测试。不同 Broker ID 携带同一事件 ID 时，MySQL 副作用只有一次。首轮失败时收据和写入共同回滚，重投后成功。[RocketMQ 集成测试](../../shop-messaging/src/test/java/org/spring/shopmessaging/RocketMqIntegrationTests.java)
- Codex App Server 以仓库根目录实际发现七个启用技能。新增商品、库存及订单场景验证专项选择；阶段 6 更新后重新发现成功。[阶段 4](stage-04-skills.md) · [阶段 6](stage-06-lifecycle.md)
- 快速门禁 134 项、真实 MySQL/Redis/RocketMQ 全量门禁 274 项，均无失败、错误或跳过。OSV 扫描 Maven 解析出的 271 个组件，无命中。Gitleaks、静态检查、actionlint 和本地 Hook 通过。[门禁记录](stage-05-quality.md) · [依赖记录](stage-05-dependency-findings.md)
- 阶段 6～8 的业务源码、资源、POM 和测试复核了同一份阶段 5 摘要，包括 265 个文件。SHA-256 为 `96f71fc3777f328e07608c238d01c04a810688e50afcf68f3bae58b61a01391f`。阶段 5 的工程变更集中在依赖和迁移适配、消息基础设施与验证。

## 部署与待办

本轮隔离验收使用本机构建的 RocketMQ 5.5.1 测试镜像与 Java SDK 5.2.2，CI 默认用官方 5.5.0 镜像。仓库尚无远端配置，托管 CI 运行及分支保护待接入远端后验证。业务消息启用时，根据已确认的订单或库存流程制定协议和迁移，并验收生产 ACL、FIFO、积压、死信重放、HA 与告警。Jackson 2 作为 Boot 4 的兼容层保留现有 JSON 行为，后续迁移依据契约测试。[消息接入](../messaging/rocketmq-integration.md) · [工程维护](maintenance.md)
