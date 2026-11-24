# 项目状态

最后更新于 **2026-11-24**。业务流程已覆盖商品、购物车、下单、库存预占、Mock 支付或关闭、履约及确认收货。本机已可查看导入的 CRM 目录。项目背景和模块职责见[项目总览](README.md)。

## 已完成

| 模块 | 当前进度与依据 |
| --- | --- |
| 用户与网关 | 注册、登录、刷新、登出、JWT/RBAC、服务路由与 Trace 已实现。见[业务入口](docs/business/README.md)、[网关配置](shop-gateway/src/main/resources/application.yml)和[用户迁移](shop-user/src/main/resources/db/migration) |
| 商品 | 分类、SPU/SKU、上下架和公开目录已实现。公开 SKU 详情有[实际接口及验证记录](docs/agent-engineering/tasks/public-sku-detail.md)，Flyway 见[商品迁移](shop-product/src/main/resources/db/migration) |
| 收货地址 | 已实现本人地址列表、新增、编辑和删除，支持地区白名单、每人 20 条上限及事务内默认地址切换。订单服务可在启用内部接口后按用户和地址 ID 读取收件信息。[接口与验证记录](docs/agent-engineering/tasks/create-shipping-address.md) |
| 库存 | 批次入库已实现，相关 MySQL 集成与网关联调证据见[入库记录](docs/agent-engineering/tasks/inventory-batch-receipt.md)，持久化合同见[库存迁移](shop-inventory/src/main/resources/db/migration/V1__create_stock_receipt_tables.sql) |
| 购物车 | shop-order 支持本人购物车列表、设置绝对数量和移除规格，每人最多 20 种，列表实时查询商品可售性。[接口与部署](docs/agent-engineering/tasks/cart-items.md) |
| 结算报价 | 校验本人收货地址、商品实时价格及可售状态，计算运费和应付金额，保存 5 分钟报价。[接口与部署](docs/agent-engineering/tasks/checkout-and-orders.md) |
| 下单与库存预占 | 支持下单与本人订单查询，RocketMQ 异步预占按 FEFO 分配，库存提供预占状态和批次分配查询。[接口与部署](docs/agent-engineering/tasks/m3-order-reservation.md) |
| 支付与关闭 | 支持 Mock 支付、签名回调、取消、超时关单、库存确认与释放，以及异常全额退款。[接口与部署](docs/agent-engineering/tasks/m4-payment-close.md) |
| 履约与联调 | 支持后台订单游标分页、详情与批次查询、登记发货及买家确认收货。[接口与部署](docs/agent-engineering/tasks/m5-fulfillment.md) |
| 稳定性与扩容 | 支持 Nacos 持久化规则、Sentinel 全局下单限流、依赖熔断、Outbox 扩容和交易巡检。双副本限流、故障恢复、死信、备份恢复和 k6 短测均已本机验证。[验收记录](docs/agent-engineering/tasks/m6-stability.md) |
| 公共基础设施 | 公共异常、日志、Trace、Redis 和内部鉴权已落地。RocketMQ 传输、事务发件箱及消费去重由订单和库存按配置启用。见[组件目录](docs/architecture/component-catalog.md)和[消息接入](docs/messaging/rocketmq-integration.md) |

## 正在开发

- **商城前端与 CRM 导入**。本机商品库保存一份含 125 个 SPU、238 个 SKU 的 CRM 快照。目录按品类展示，分类翻页和搜索由 MySQL 查询，已通过真实网关和浏览器验收。5 项 Node 检查通过。[任务记录](docs/agent-engineering/tasks/shop-web-catalog-import.md)

## 待完成

- 正式销售目录仍需核对 CRM 报价缺口、配料、储存、保质期与超限规格的业务规则。[任务记录](docs/agent-engineering/tasks/shop-web-catalog-import.md)
- 正式容量指标待目标硬件与数据集验收。真实支付渠道按需求推进。[容量场景](performance/README.md)

## 技术债务与风险

- **技术债务**。公开 SKU 的 `estimatedAvailable` 目前为 `null`，尚未关联可用库存。旧设计和 OpenAPI 覆盖的范围大于当前实现，需按 Controller、配置和测试确认实际能力。[SKU 交付记录](docs/agent-engineering/tasks/public-sku-detail.md)
- **验证边界**。早期库存和 CRM 目录演练使用静态服务发现，M6 网关已通过三节点 Nacos 发现双副本。Harness 的[历史运行基线](docs/agent-engineering/evidence/runtime-baseline.json)与当前 Java 源码输入指纹不同，完整工程门禁与 OSV 需独立复跑。阶段的已存记录见[建设检查点](docs/agent-engineering/checkpoint.md)。
- **工作树**。前端及任务文档含未提交文件，CRM 快照文件由 Git 忽略。切换工作树前核对实际文件。[任务索引](docs/agent-engineering/tasks/README.md)
