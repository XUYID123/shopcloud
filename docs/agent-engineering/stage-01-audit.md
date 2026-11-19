# 阶段 1 项目审计

2026-11-19 · 工作区 springcloud-demo · 依据源码、POM、迁移、Compose 和测试配置静态检查。

## 架构和技术栈

根 [pom.xml](../../pom.xml) 聚合九个模块。[基础父 POM](../../shop-base-parent/pom.xml) 定义 Java 17、Boot 3.2.5、Cloud 2023.0.1、Alibaba 2023.0.1.0、Flyway 10.10.0、MyBatis starter 3.0.3 和 MySQL Connector/J 8.3.0。user 使用 MyBatis-Plus 3.5.7。common、cache、gateway 走无数据库默认依赖的基础父层。业务服务按 Controller → Service → Mapper/XML → 独立 schema 分层。Gateway 是 WebFlux 服务，Nacos 做服务发现，Sentinel 做容量控制。路由、匿名规则、可信代理、Redis 分实例和 JWT 的入口见 [网关配置](../../shop-gateway/src/main/resources/application.yml)。

| 模块 | 代码依据和现状 |
|---|---|
| gateway | [网关包](../../shop-gateway/src/main/java/org/spring/shopgateway) 包含请求清洗、防刷、外部 JWT/RBAC、内部 JWT 签发和路由；权限缓存冷加载使用 Feign 调 user |
| user | [用户包](../../shop-user/src/main/java/org/spring/shopuser) 实现注册、登录、刷新、退出和内部权限快照；角色管理业务待建 |
| product | [商品包](../../shop-product/src/main/java/org/spring/shopproduct) 实现分类、SPU/SKU 新建、上下架、公开列表和详情；库存和通用编辑待建 |
| cart / order / pay | [cart](../../shop-cart/src/main/java/org/spring/shopcart)、[order](../../shop-order/src/main/java/org/spring/shoporder)、[pay](../../shop-pay/src/main/java/org/spring/shoppay) 为应用骨架及上下游验签接入 |
| common / cache | [common](../../shop-common/src/main/java/org/spring/shopcommon) 为跨服务基础组件；[cache](../../shop-cache/src/main/java/org/spring/shopcache) 为可选同步缓存库，商品使用自己的详情缓存 |

[用户 Compose](../../shop-user/compose.yaml) 和 [商品 Compose](../../shop-product/compose.yaml) 各启 MySQL 8.4.11（默认回环端口 3307、3308）。[网关 Compose](../../shop-gateway/compose.yaml) 启两个 Redis 8.2.10，6380 安全域、6381 缓存域。[common Compose](../../shop-common/compose.yaml) 保留 MySQL 脚手架。未发现统一应用容器部署或 RocketMQ 服务。

## 公共组件

| 需求 | 实际入口和用法 |
|---|---|
| 响应和异常 | [Result / GlobalExceptionHandler](../../shop-common/src/main/java/org/spring/shopcommon/result) 提供 MVC 统一响应，抛 [BusinessException / ErrorCode](../../shop-common/src/main/java/org/spring/shopcommon/exception)；WebFlux 由 [gateway error](../../shop-gateway/src/main/java/org/spring/shopgateway/error) 写响应 |
| 日志 | [logging](../../shop-common/src/main/java/org/spring/shopcommon/logging) 的 ServletAccessLog、@BizLog、BizLogAspect 和 LogValues 处理访问、结果码和业务审计；[trace](../../shop-common/src/main/java/org/spring/shopcommon/trace) 提供 TraceContext、Servlet/Feign 透传及 MDC TaskDecorator |
| 校验 / DTO / VO | [商品入口](../../shop-product/src/main/java/org/spring/shopproduct/controller) 通过 Jakarta Validation 校验 DTO，响应使用专用 VO；[ProductListQuery 与 ProductPageVO](../../shop-product/src/main/java/org/spring/shopproduct) 为模块分页契约 |
| 安全 | [用户 JWT/刷新](../../shop-user/src/main/java/org/spring/shopuser/auth)、[网关鉴权](../../shop-gateway/src/main/java/org/spring/shopgateway/auth)、[内部验签](../../shop-common/src/main/java/org/spring/shopcommon/internalauth) 形成信任链；使用 Security Crypto、Security JWT 相关库及自定义过滤逻辑，以实际装配判定生效 |
| 缓存/防重 | [SafeCacheTemplate](../../shop-cache/src/main/java/org/spring/shopcache/SafeCacheTemplate.java) 用于可选同步缓存；[IdempotentAspect / RedisIdempotentStore](../../shop-common/src/main/java/org/spring/shopcommon/idempotent) 用于时间窗口去重（SET NX + TTL）；持久化业务幂等另行设计 |
| 事务 | [UserService](../../shop-user/src/main/java/org/spring/shopuser/service/UserService.java) 用 TransactionTemplate；[商品 Service](../../shop-product/src/main/java/org/spring/shopproduct/service) 使用声明式事务或只读快照。未见全局分页组件、ApiResponse 类或 MQ 封装 |

## MySQL、Redis 与一致性

- [用户迁移](../../shop-user/src/main/resources/db/migration) V1 为账号、角色、权限及两张关联表，账号名、角色码、权限码唯一，关联表复合主键和外键。V2 初始化权限，V3 建权限失效任务，复合主键 user_id/key_prefix。注册账号、分配角色和记录失效同事务，唯一键在并发下兜底。
- [商品迁移](../../shop-product/src/main/resources/db/migration) V1～V4 为分类、SPU、SKU 和缓存失效表。分类名与 SKU 编码唯一，SPU → 分类、SKU → SPU 有外键。列表和规格查询相关索引分别覆盖分类、销售态或启用态。商品写入对分类 FOR SHARE，新建 SKU 和上下架串行锁定 SPU，上下架再做 version 条件更新。[Mapper XML](../../shop-product/src/main/resources/mapper)
- 6380 为 AOF/noeviction，承载防刷 ZSET/Lua、刷新会话和公共短期防重。6381 为无持久化/allkeys-lfu，承载 RBAC 和业务缓存。RBAC 使用版本化 Key、TTL、Redisson 冷加载锁、Lua epoch 检查和 Feign 回源。权限失效任务回滚随业务事务回滚，提交后尝试失效并定时补偿。[防刷 Lua](../../shop-gateway/src/main/resources/redis/anti-abuse.lua) · [权限失效](../../shop-user/src/main/java/org/spring/shopuser/service/PermissionCacheInvalidator.java)
- 商品详情字符串缓存按 [ProductCacheKeys](../../shop-product/src/main/java/org/spring/shopproduct/cache/ProductCacheKeys.java) 构造 Key，负缓存 3 分钟、详情 60 分钟加 0～600 秒抖动。Redis 读异常回源 MySQL。写路径在事务内登记失效，worker 删除后更新确认，异常保留任务供补偿；并发读回填与延期删除间仍有短暂陈旧窗口。[商品缓存](../../shop-product/src/main/java/org/spring/shopproduct/cache)
- [缓存库](../../shop-cache/src/main/java/org/spring/shopcache) 支持 Redisson 单键冷加载、短空值 TTL、正常 TTL 抖动、可配置故障策略和 Resilience4j 隔离；这些能力须逐使用点确认。
- RocketMQ 未接入。POM、生产源码、配置和 Compose 中无客户端、NameServer/Broker、Producer/Consumer 或消息协议。旧商城方案里的 RabbitMQ 为设计记录。现有两张 outbox 是 Redis 失效任务。未来 DB 与 MQ 双写会出现提交后未发、回滚却已发；发送超时与消费提交后 ACK 失败会产生重复。设计需要持久化投递状态、稳定事件 ID、消费唯一约束及重放。当前没有事务消息、MQ 补偿、顺序/重试/DLQ、积压监控和 Broker HA 的运行证据。

## 工程质量和风险

基础父 POM 继承 JUnit Jupiter、Mockito 和 Surefire 3.2.5。现有 [用户测试](../../shop-user/src/test)、[商品测试](../../shop-product/src/test)、[网关测试](../../shop-gateway/src/test)、[缓存测试](../../shop-cache/src/test) 涵盖部分真实 MySQL/Redis、唯一约束、事务、并发和故障；通过测试源码区分覆盖与实际运行结果。未发现 Testcontainers、仓库 CI、格式/静态检查、依赖扫描。Git 当前 main 尚无首个提交，已有暂存的 IDEA 文件、大量未跟踪源码及构建/日志文件。密钥路径区分 [测试夹具](../../infra/security/test-fixtures) 与 [本地密钥目录](../../infra/local-secrets)；Compose/应用存在开发凭据配置，审计未复述值或判断其线上使用状态。

| 优先级 | 风险和验收关注 |
|---|---|
| P0 | 本次静态审计未确认在运行功能中的 P0。库存/支付属于待实现范围，未来超卖、重复扣减和跨用户写入为否决条件 |
| P1 | Redis 失效任务积压可延长商品陈旧或权限传播；验证失败重试、延期窗口及监控。运行环境需要核对密钥、网络隔离和开发示例口令隔离 |
| P2 | 缓存故障回源放大 MySQL 压力；补充并发与超时预算。构建、质量扫描及集成测试缺乏集中门禁 |
| P3 | 历史文档将新版本/RabbitMQ 写为推荐方案，以 POM/迁移为事实来源；清理生成物纳入后续日常维护 |

Agent 容易把设计契约当接口，把 Redis 防重当持久化幂等，把失效 outbox 当 MQ，或为简单商品写入新增中间件。进入设计前核对实际 Controller、事务、唯一约束、鉴权和已有失败覆盖。

## 自检

回查各模块 POM、路由、V1～V4/V1～V3 迁移、事务注解/锁 SQL、测试路径和两个 Redis 配置。审计仅落盘任务书、报告与状态文件。链接校验通过后在检查点记录 PASS。
