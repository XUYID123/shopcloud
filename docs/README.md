# 面粉商城：微服务学习与实施文档

## Agent 工程知识库

[项目速览](../README.md) · [项目状态](../PROJECT_STATUS.md) · [会话交接](../SESSION_HANDOFF.md) · [本地快速启动](architecture/local-development.md) · [任务索引](agent-engineering/tasks/README.md) · [跨会话验收](agent-engineering/context-initialization-acceptance.md)

当前框架和消息接入以[架构](architecture/README.md)和[消息](messaging/README.md)为准。基线审计保留建设初始快照，根目录版本变更以 POM 为准。

初始实现参阅 [项目审计](agent-engineering/stage-01-audit.md)。下方历史说明中的推荐版本、RabbitMQ、订单和支付按当时规划阅读。

- [架构](architecture/README.md) · [公共组件](architecture/component-catalog.md)
- [工程规则](standards/README.md) · [Definition of Done](standards/definition-of-done.md)
- [数据库](database/README.md) · [Redis](middleware/README.md)
- [RocketMQ](messaging/README.md) · [数据库与消息一致性](messaging/database-consistency.md)
- [安全](security/README.md) · [业务](business/README.md) · [ADR](decisions/README.md)
- [建设检查点](agent-engineering/checkpoint.md) · [工程规范维护](agent-engineering/maintenance.md)
- [任务路由](agent-engineering/workflows/task-routing.md) · [工程建设与恢复](agent-engineering/construction-workflow.md) · [日常任务记录](agent-engineering/tasks/README.md) · [验收证据](agent-engineering/evidence/README.md)

## 历史实施记录（截至 2026-11-02）

初始设计日期为 2026-10-13。当时状态为部分实现，其余内容属于设计与接口契约。

shop-user 的 Docker MySQL、Flyway RBAC 迁移及用户注册接口已落地，见 08/09 文档；五个业务服务的 Nacos 注册及网关转发配置已落地，见 10 文档；网关 Sentinel 容量保护和 Redis 防刷见 11 文档。完整商城和其余接口仍待实现。当时使用 JDK 17 / Boot 3.2.5；user 已接入 MyBatis-Plus 3.5.7，其余业务默认 MyBatis 3.0.3，与下方目标架构分开说明。OpenAPI 版本为 1.11.0-partial。

网关公钥 JWT 认证与动态 RBAC 已实现，最新方案为 Redis 权限缓存、OpenFeign 回源 shop-user、数据库更新后删除缓存，见 17 文档。Caffeine 与权限广播已移除；用户注册接入事务失效 outbox。JJWT 双 Token 登录/刷新/登出已实现，见 20 文档；角色管理仍待业务阶段实现。

所有下游已强制内部 JWT 验签，只有网关持内部签名私钥；迁移见 [24 文档](24-网关内部JWT与下游统一验签.md)。

商品分类创建与公开列表查询已实现；GET /api/v1/categories 只返回启用分类的 categoryId/name/sort，见 [25 文档](25-商品分类列表查询.md)。新增商品 SPU 接口 POST /api/v1/admin/products 已接入未上架创建、分类校验与内部 JWT，见 [26 文档](26-新增商品SPU接口.md)。新增 SKU 接口 POST /api/v1/admin/skus 已实现规格、价格与编码全局唯一校验，库存后续接入，见 [27 文档](27-新增商品SKU接口.md)。商品上架/下架 PUT /api/v1/admin/products/{productId}/sale-status 已实现上架条件、版本控制与最新规格响应，见 [28 文档](28-商品上架与下架接口.md)。公开商品详情 GET /api/v1/products/{productId} 已实现销售可见性与启用规格过滤，见 [29 文档](29-公开商品详情查询接口.md)。

外部入口复用合法 X-Trace-Id 或生成 16 位随机关联号，X-Request-Id 为同值兼容头；保留原身份/代理清洗和 JWT/RBAC 规则。公共 Servlet、Feign、Reactor/MDC、异步线程池和控制台/文件日志已接通，见 [21 文档](21-全链路TraceId与异步上下文.md)。

目标是做一个单商家、单仓库、袋装面粉商城，在可控的开发量内跑通交易，学习真实的服务拆分、库存并发控制、消息可靠性和故障恢复。企业级要求落实在交易正确性、可观测性、部署和验收上。

## 阅读顺序

1. [技术选型与架构](01-技术选型与架构.md)：版本、组件、服务职责、部署与高并发方案。
2. [业务与数据设计](02-业务与数据设计.md)：范围、业务规则、状态机、库存与支付一致性、表结构。
3. [接口文档](03-接口文档.md)：接口清单、请求与响应示例、鉴权、幂等、内部调用及事件。
4. [实施与验收](04-实施与验收.md)：开发顺序、演示数据、验收案例、压测与生产化清单。
5. [OpenAPI 3.0.3 契约](openapi.json)：可导入支持 OpenAPI 的接口工具。定义第一版接口，不能把导入成功理解成后端已实现。
6. [Nacos 与 Sentinel 落地方案](05-Nacos与Sentinel落地方案.md)：依赖、环境隔离、配置加载、规则持久化、限流与熔断、部署及验收。
7. [开发任务与编码步骤](06-开发任务与编码步骤.md)：结合当前代码拆分首轮任务、类与接口、实现顺序和验收标准，从工程修正与已有 shop-user 的注册登录开始。
8. [第一步工程调整操作手册](07-第一步工程调整操作手册.md)：按 IDEA 操作拆解模块坐标、代码移动、common 精简、用户服务依赖及启动验收。
9. [Docker 数据库与 Flyway 启动](08-Docker数据库与Flyway启动.md)：当前已添加的用户服务开发配置、应用自动启动 MySQL、RBAC 迁移和 IDEA 连接步骤。
10. [用户注册实现与验收](09-用户注册实现与验收.md)：当前注册代码、事务、统一响应调整、实际 HTTP 行为、请求示例和集成测试。
11. [Nacos 注册与网关启动](10-Nacos注册与网关启动.md)：当前各服务的端口、依赖结构、注册配置、IDEA 启动和网关转发规则。
12. [网关限流与 Redis 防刷](11-网关限流与Redis防刷.md)：Sentinel 容量限制、共享 Redis 滑动窗口与封禁、可信代理、失败策略、启动和测试。
13. [网关代码流程阅读说明书](12-网关代码流程阅读说明书.md)：按一次注册请求解释执行顺序、配置与代码字段、响应式操作符、Redis 键和 Lua 每个分支，并提供 IDEA 跟读步骤。
14. [POM 继承与依赖管理说明](13-POM继承与依赖管理说明.md)：业务服务默认继承 MyBatis/MySQL/Flyway/Lombok，gateway/common 通过基础父 POM 隔离数据库依赖，同时保留按需引入。
15. [Docker 双 Redis 实例与连接说明](14-Docker双Redis实例与连接说明.md)：6380 用于 IP 防刷，6381 用于业务缓存及 RBAC 权限缓存，说明端口、密码、持久化、淘汰策略与接入方式。
16. [配置驱动防刷策略设计](15-配置驱动防刷策略设计.md)：用策略解析接口、配置规则表和独立执行器解耦，新增接口只需配置，说明匹配、叠加、顺序、迁移与更新方式。
17. [网关 JWT 与动态 RBAC 历史实现](16-网关JWT与动态RBAC实现.md)：保留 Caffeine/广播旧方案和阶段验收，缓存实现已由下一篇替代。
18. [Redis 权限冷加载与 OpenFeign](17-Redis权限冷加载与OpenFeign.md)：当前权限链、Redis 数据、内部 Feign 查询、MySQL 有效权限 SQL、事务后失效、重试、并发冷加载及配置迁移。
19. [SafeCacheTemplate 公共缓存组件](18-SafeCacheTemplate公共缓存组件.md)：按需引入的同步 RedisTemplate/Redisson 三防缓存模板、Resilience4j 重试/超时/熔断、显式降级、配置与 Service 示例。
20. [网关职责重构与生产治理](19-网关职责重构与生产治理.md)：目标包结构、鉴权用例/权限端口/纯策略边界、统一 WebFlux 异常响应、CORS 和分布式追踪接入方案。
21. [双 Token 登录刷新与 JJWT](20-双Token登录刷新与JJWT.md)：MyBatis-Plus/BCrypt 登录、RS256 Access Token、6380 Refresh Token 会话、原子轮换、登出、Servlet 拦截器及网关兼容。
22. [全链路 Trace ID 与异步上下文](21-全链路TraceId与异步上下文.md)：统一 X-Trace-Id、兼容头、Reactor/MDC、Servlet、Feign、装饰线程池、共享日志与真实验收。
23. [公共幂等注解与 Redis 防重](22-公共幂等注解与Redis防重.md)：方法级 SpEL Key、原子占位、Boot SPI、6380 安全存储、409/503 与时间窗口边界。
24. [商品分类创建接口](23-商品分类创建接口.md)：商品独立数据库/Flyway、MyBatis 创建分类、网关权限与防绕过、并发重名、接口调用及验收。
25. [网关内部 JWT 与下游统一验签](24-网关内部JWT与下游统一验签.md)：替代静态内部凭据，45秒 RS256、受众和请求绑定、全服务强制过滤器、公私钥隔离及迁移。
26. [商品分类列表查询](25-商品分类列表查询.md)：匿名读取启用分类、稳定排序、公开 VO、内部 JWT 保护和查询验收。
27. [新增商品 SPU 接口](26-新增商品SPU接口.md)：九字段输入、分类事务校验、Flyway 商品表、默认下架/版本、权限规则和入库验收。

28. [新增商品 SKU 接口](27-新增商品SKU接口.md)：规格与整数定价、编码并发唯一性、父商品事务校验、权限规则和入库验证。

29. [商品上架与下架接口](28-商品上架与下架接口.md)：上架条件、版本CAS、跨记录事务保护、完整规格响应与并发验证。

30. [公开商品详情查询](29-公开商品详情查询接口.md)：匿名详情、销售可见性、启用规格、只读快照与网关验证。

31. [访问日志与业务日志](30-访问日志与BizLog业务日志.md)：公共 @BizLog、事务结果确认、安全访问日志、异步输出及 Trace ID 关联验证。

32. [业务异常码与访问日志统一](31-业务异常码与访问日志统一.md)：MVC/过滤器/网关统一出口、status+code+TraceID、框架错误和下游转发边界。

## 推荐方案

后端：Java 21、Spring Boot **4.0.8**、Spring Cloud **2025.1.3**、Spring Cloud Alibaba **2025.1.0.0**、Gateway、**Nacos Server 3.2.4**、**Sentinel 1.8.9**、Spring Security、MyBatis 4.0.0、MySQL 8.4 LTS、Redis 8.2、RabbitMQ 4.x。Nacos Client 3.1.1 由 Alibaba BOM 管理，与服务端版本分别维护。

HTTP 接口统一采用 **RESTful API + JSON**：以资源和子资源命名，按 GET/POST/PUT/DELETE 语义调用，正确使用 HTTP 状态码、Location、鉴权及幂等键。异步取消申请有独立查询资源；支付渠道回调遵循渠道协议。

Nacos 从第一阶段承担服务注册发现与集中配置；Sentinel 从第一阶段承担网关/业务资源流控、慢调用与异常熔断，规则存入 Nacos。版本基线按 Alibaba 官方适配的 Boot 4.0.x 分支选择，不能仅凭 Cloud 支持 Boot 4.1 就推定 Alibaba 全部适配。

前端：Vue 3 + TypeScript + Vite，消费者页面与管理页面共用一个项目。前端最终小版本在搭建时锁定；第一阶段可以只使用接口工具演示业务。

第一版：用户注册登录与地址、商品与规格、购物车、按重量计算运费、下单预占库存、模拟支付、超时关闭、取消订单、后台发货、确认收货。可靠消息、业务幂等、批次追溯和异常补偿随交易一起实现。

后续：真实支付、常规退款退货、多仓、批发客户分级价、优惠券、发票、搜索引擎、秒杀、Kubernetes。真实支付上线前必须完成渠道适配、验签、查单、关单、退款和对账验收。

## 当前项目与方案之间的差距

| 发现 | 实施时的处理 |
|---|---|
| 根 POM 使用 Java 17 / Boot 3.2.5 / Cloud 2023.0.1 | 基础设施阶段统一升级并锁定兼容版本；旧示例仅用于理解概念 |
| `shop-user` 坐标已修正，并引用 common | 当前用户注册阶段已完成；其他模块实施时继续统一坐标与依赖 |
| `shop-common` 产出普通 JAR，已移除数据库与 Compose 依赖，仍保留当前启动类及 Web 代码 | 作为公共库引用，数据库与基础设施由各服务自己管理，不作为独立业务服务注册 |
| MySQL 镜像使用 `latest`，端口为动态映射，配置包含示例口令 | 固定版本/镜像摘要，开发端口映射到 127.0.0.1；口令改用环境变量 |
| 基础父 POM 统一版本和插件，五个业务服务默认继承数据库依赖；gateway/common 单独继承基础层隔离数据库 | POM 架构见 13 文档；四个未接库的骨架服务暂时排除数据库自动装配；shop-pay 对应方案的 shop-payment |

建议从“修正工程骨架 + 用户/商品接口”开始，每个阶段都能启动和演示，再逐步完成交易可靠性。

初始设计交付记录：OpenAPI 1.1.0-design 的 49 个接口、85 个 schema、581 处本地引用曾通过结构检查，文字接口清单覆盖 49/49。当前版本及实现验证以 08/09/10/11 文档为准：Nacos 注册与网关转发、Sentinel 网关容量限制和 Redis 防刷已验证，集中规则发布、业务熔断、完整交易与性能压测仍待实施。
