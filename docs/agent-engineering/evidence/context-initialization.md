# 跨会话初始化证据

2026-11-20 · 验收使用同一工作树上的隔离 Codex 上下文，启动时无历史对话。验收代理只读，任务提示只给出工作区、验收问题和假设的订单设计请求。对仓库的判断来自打开的项目文件。

| 检查项 | 会话观察与核对依据 |
|---|---|
| 1. 项目目标 | 能说明单商家、单仓库的袋装面粉商城及工程验证目标。[README](../../../README.md) |
| 2. 技术栈 | 从[基础父 POM](../../../shop-base-parent/pom.xml)确认 Java 17、Boot 4.0.9、Cloud 2025.1.4、Alibaba 2025.1.0.0。区分 Docs 中的历史 Java 21、RabbitMQ、Vue 目标 |
| 3. 模块关系 | 从 [Maven reactor](../../../pom.xml)、[网关路由](../../../shop-gateway/src/main/resources/application.yml)和[前端代理](../../../shop-web/server.mjs)确认模块和调用关系。库存有独立 MySQL 与[迁移](../../../shop-inventory/src/main/resources/db/migration/V1__create_stock_receipt_tables.sql) |
| 4. 公共异常 | 定位 MVC 的 [GlobalExceptionHandler](../../../shop-common/src/main/java/org/spring/shopcommon/result/GlobalExceptionHandler.java)和网关的 [GatewayErrorWebExceptionHandler](../../../shop-gateway/src/main/java/org/spring/shopgateway/error/GatewayErrorWebExceptionHandler.java)。以三个 MVC 服务启动类的实际导入为准 |
| 5. 日志规范 | 找到[日志规则](../../standards/README.md)、[日志与 BizLog](../../30-访问日志与BizLog业务日志.md)及 [BizLogAspect](../../../shop-common/src/main/java/org/spring/shopcommon/logging/BizLogAspect.java)。能指出 Trace 与敏感字段边界 |
| 6. Redis | 从[网关 Compose](../../../shop-gateway/compose.yaml)确认 6380 安全域、6381 缓存域。定位 [AntiAbuseFilter](../../../shop-gateway/src/main/java/org/spring/shopgateway/protection/AntiAbuseFilter.java)、[RbacPermissionCache](../../../shop-gateway/src/main/java/org/spring/shopgateway/auth/RbacPermissionCache.java)和[商品详情缓存](../../../shop-product/src/main/java/org/spring/shopproduct/cache/ProductDetailCache.java) |
| 7. RocketMQ | 确认[传输和消费去重](../../messaging/rocketmq-integration.md)已集成。[订单配置](../../../shop-order/src/main/java/org/spring/shoporder/messaging/OrderRocketMqConfiguration.java)的 Producer 条件启用，当前交易事件仍在规划 |
| 8. 项目进度 | 从[项目状态](../../../PROJECT_STATUS.md)、[任务记录](../tasks/README.md)及历史验证分辨已交付的 SKU/库存、待 CRM 数据与待联调范围。历史 Maven/RocketMQ 输入与当前工作树不同 |
| 9. Skills | 从[入口](../skills.md)定位七个文件，核对文件与[发现证据](skill-discovery.json)。订单预占设计读取数据库和并发技能，Redis 与 RocketMQ 按设计范围使用 |
| 10. 路由和资料 | 假设需求仅要订单创建和库存预占方案，选择 ROUTE-08、只读设计。阅读[业务设计](../../02-业务与数据设计.md)、[当前业务](../../business/README.md)、[数据库](../../database/README.md)、[ADR](../../decisions/README.md)及库存服务与现有测试 |

实际读取根目录 [AGENTS](../../../AGENTS.md)、[README](../../../README.md)、[PROJECT_STATUS](../../../PROJECT_STATUS.md)、[SESSION_HANDOFF](../../../SESSION_HANDOFF.md)及当前工作树。验收代理确认 `main` 基准提交 `6b3c48a`，现有未提交文件可见。它看到了 CRM 交接和本地待审占位状态 `AWAITING_CRM_EXPORT`，在订单设计请求里保持原任务范围；对明确续做的假设请求，核对前端、4 项既有检查、待审数据和下一步，仍需取得 CRM 导出或明确允许读取的页面与字段，写库前等待审核与目标库确认。

本机 App Server 对照尝试成功创建 0 条历史的临时只读会话，但 5 次命令执行调用失败，文件读取验收受阻。其工具错误与后续隔离会话的验证结论分别保留在[验收报告](../context-initialization-acceptance.md)。该测试范围是接手流程、事实定位和路由，部署与当前后端运行仍以实际联调报告为准。
