# 阶段 5 质量门禁验收

**STAGE_5_STATUS = PASS** · 2026-11-19。此前阶段 5 因旧依赖扫描告警和缺少 RocketMQ 实测停在 [阶段 4](stage-04-skills.md)。本轮按[恢复方案](stage-05-resume-design.md)完成依赖升级、接入和隔离复验。

## 交付

- Java 17、Spring Boot 4.0.9、Spring Framework 7.0.10、Cloud 2025.1.4、Alibaba 2025.1.0.0，MyBatis 4.0.1、MyBatis-Plus 3.5.18，以及 Apache HttpClient 5.6.3 / HttpCore 5.4.3。Jackson 2 兼容模块保留现有接口 JSON 和严格字段转换。生产迁移沿用原有 Flyway 文件。
- [RocketMQ 接入](../messaging/rocketmq-integration.md)提供 5.2.2 SDK、同步回执、Tag 订阅、MySQL 同事务消费收据；shop-order 显式启用 Producer。正常消息的去重、失败重试和回滚由真实 Broker + MySQL 验证。
- [门禁脚本](../../infra/quality/gate.py)管理随机 Compose 测试栈。[测试配置](../../infra/quality/compose.yaml)把 NameServer、Broker 和 Proxy 分开，只有 Proxy gRPC 对回环暴露。CI 通过实际 Maven SBOM 查依赖告警，测试直接使用现有 JUnit、Mockito 和 Surefire。
- Gateway 5 迁移保留入口身份与地址清洗，出口过滤器按 exchange 中已校验 IP 恢复转发头。原有 Redis 双实例、RBAC、JWT 与限流的测试全部复验。

## 执行结果

| 检查 | 证据 |
|---|---|
| 快速门禁 | Maven verify 134 项，失败、错误和跳过均为 0。日志 `target/harness-boot4-quick-final.log` |
| 完整门禁 | Maven verify 274 项，失败、错误和跳过均为 0。日志 `target/harness-boot4-full-final.log` |
| MySQL 与 Redis | 完整门禁覆盖 Flyway、约束、事务、并发、TTL、缓存补偿、防刷和 Redis 故障 |
| RocketMQ | [两个真实用例](../../shop-messaging/src/test/java/org/spring/shopmessaging/RocketMqIntegrationTests.java)通过。同 ID 两条真实投递只成功写一次；首轮写入故障时收据和副作用一并回滚，Broker 重投后提交成功 |
| 依赖扫描 | [实际 Maven SBOM](stage-05-dependency-findings.md)含 271 个组件，OSV 输出 No issues found |
| 敏感信息 | Gitleaks 8.30.1 脱敏工作树扫描通过，公用测试夹具按摘要校验 |
| Hook / CI | Git Hook 本地运行通过，actionlint 校验工作流通过。仓库无远端及首次提交，托管运行和分支保护待仓库配置 |

首次 Docker Hub 请求失败，本机改用已按 Apache 官方 SHA-512 确认的 Broker 5.5.1 发行包构建临时测试镜像，CI 默认配置为官方 5.5.0 镜像。修复了 Proxy 路由广告返回容器地址的问题，已确认 Compose 栈容器、网络和测试卷由门禁清理。[本地复验说明](quality-gate.md)

业务专用 FIFO、死信重放、积压和 HA 故障验收需按实际业务协议和生产拓扑实施。后续场景以设计演练验证这些要求。

## Harness 2.0 复核

- [stage-05]: ACCEPTED · 2026-11-20 · 阶段 5 应用范围与既有 Maven、RocketMQ、OSV 原始日志匹配。当前托管工作流运行情况为 NOT_RUN。
