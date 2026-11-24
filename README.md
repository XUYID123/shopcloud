# 面粉商城 · springcloud-demo

单商家、单仓库的袋装面粉商城，用于验证微服务拆分、商品与库存一致性、鉴权、可观测性和消息可靠性。

当前后端使用 Java 17、Maven、Spring Boot 4.0.9、Spring Cloud 2025.1.4 和 Spring Cloud Alibaba 2025.1.0.0。网关使用 WebFlux，服务发现用 Nacos，业务数据用 MySQL、Flyway 和 MyBatis 系列组件，Redis 用于防刷、会话和缓存，RocketMQ 为按需启用的消息传输。前端 `shop-web` 使用 Node.js 和原生网页，独立于 Maven。版本以 [根 POM](pom.xml)、[基础父 POM](shop-base-parent/pom.xml)和[前端脚本](shop-web/package.json)为准。

商城请求从浏览器经 `shop-web` 到网关，再由网关路由到用户或商品服务。商品管理分类、SPU 和 SKU，库存入库按 SKU 向商品服务查元数据，再在独立库存库记批次、余额和流水。详见[整体架构](docs/architecture/README.md)和[业务接口](docs/business/README.md)。

| 模块 | 当前职责 |
| --- | --- |
| `shop-gateway` | 统一路由、JWT 与动态 RBAC、限流防刷、Trace 和内部请求签名 |
| `shop-user` | 用户注册、登录、刷新、登出及权限查询 |
| `shop-product` | 分类、SPU 和 SKU 管理，上下架，公开商品列表、详情和 SKU 详情 |
| `shop-inventory` | 批次入库、库存预占与释放、库存汇总和流水 |
| `shop-web` | 浏览、分类筛选、搜索、规格选择、登录和本地购物袋 |
| `shop-order` | 服务端购物车、结算报价、下单、关单、前后台订单查询、发货和确认收货 |
| `shop-pay` | Mock 支付、签名回调、关单墓碑及异常全额退款 |
| `shop-cart` | 保留的应用骨架 |
| `shop-base-parent`、`shop-common`、`shop-cache`、`shop-messaging` | 构建与依赖基线、公共响应及基础设施、缓存、消息传输与消费幂等 |

## 开发路线

- **已实现** 用户身份、本人收货地址管理、商品目录及公开 SKU 查询、库存批次入库、服务端购物车、结算报价、下单、库存预占、Mock 支付与关闭、发货及确认收货。工作树中的商城前端支持目录浏览和本地购物袋，结算页可核对采购清单。
- **进行中** 已完成本机 CRM 目录导入和只读预览。正式销售商品资料仍待核对。当前证据、风险和待办见[项目状态](PROJECT_STATUS.md)。
- **稳定性与扩容** 支持 Nacos 持久化规则、Sentinel 全局下单配额、交易巡检和受控重放。[运行与验收](docs/agent-engineering/tasks/m6-stability.md)
- **计划中** 目标规模的容量验收及真实支付渠道。交易流程详见[业务与数据设计](docs/02-业务与数据设计.md)。

## 目录与本机运行

服务源码在各 `shop-*` 模块，前端在 `shop-web/`，本地工具与隔离验证在 `infra/`，详细文档在 `docs/`，项目技能在 `.agents/skills/`。本机按[快速启动](docs/architecture/local-development.md)配置 JDK、Node、Docker、Nacos、开发密钥和端口。只预览页面时，从根目录运行 `npm.cmd --prefix shop-web run dev`。

## 接手顺序

1. 先读 [AGENTS.md](AGENTS.md) 和目标模块的局部规则。
2. 读[项目状态](PROJECT_STATUS.md)和[会话交接](SESSION_HANDOFF.md)，按当前请求核对 Git 工作树、任务证据和授权。
3. 从 [Docs 索引](docs/README.md)、[组件目录](docs/architecture/component-catalog.md)和 [Skills 入口](docs/agent-engineering/skills.md)按需下钻。首次建设或全量升级 Harness 时看[建设检查点](docs/agent-engineering/checkpoint.md)。

Docs 索引保留了 2026-10-13 起始设计和截至 2026-11-02 的历史实施快照。历史 Java 21、RabbitMQ 和 Vue 方案用于理解当时的目标，当前版本、消息接入与前端实现以本页链接的代码及配置为准。
