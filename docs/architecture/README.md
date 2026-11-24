# 当前架构

Java 17 / Boot 4.0.9 / Cloud 2025.1.4 / Alibaba 2025.1.0.0。根 reactor 聚合基础父层、common、cache、messaging、gateway、user、product、inventory、cart、order、pay。common、cache 和 messaging 为库，Node.js 前端 shop-web 独立运行。Gateway 走 WebFlux 并通过 Nacos 发现服务，Gateway 的 RBAC 冷加载走 Feign 到 user。user、product、inventory 和 order 分别管理自己的 MySQL schema。

请求链为入口清洗与 Trace → Sentinel、防刷、JWT/RBAC → 内部 JWT → Servlet 验签 → Controller 校验 DTO → Service 执行业务与事务 → Mapper/XML → VO 和统一响应。业务包按 controller、service、mapper、entity、dto、vo 分层，基础设施按 auth、cache、trace、logging 划分。参阅[配置](../../shop-gateway/src/main/resources/application.yml)、[公共组件](component-catalog.md)与[审计](../agent-engineering/stage-01-audit.md)。

库存入库用限定范围的服务 JWT 从商品服务读 SKU 元数据，网络调用结束后才进入库存写事务。详见[库存批次入库](../agent-engineering/tasks/inventory-batch-receipt.md)。

购物车由订单服务持久化。它用独立服务 JWT 批量读取商品状态和现价，结算时再复核。[购物车接口](../agent-engineering/tasks/cart-items.md)

结算报价由订单服务分别向用户服务和商品服务读取本人地址、实时价格和版本，再将计算结果作为快照持久化。[结算报价接口](../agent-engineering/tasks/checkout-and-orders.md)

订单及库存通过 RocketMQ 普通消息、事务 Outbox 和 Inbox 完成预占和结果回传。库存服务在自己的库按 SKU 锁定库存汇总并按 FEFO 分配批次。[M3 架构和配置](../agent-engineering/tasks/m3-order-reservation.md)

订单和支付通过独立的 RocketMQ Topic 编排收款、关单和退款。支付控制行串行化收款与关闭；只有渠道关闭后才触发库存释放。[M4 架构和配置](../agent-engineering/tasks/m4-payment-close.md)

履约由订单服务写入。发货前通过受限的内部 JWT 查询库存批次，同一个订单库事务保存物流单、批次快照与订单状态。[M5 架构和配置](../agent-engineering/tasks/m5-fulfillment.md)

网关支持 Nacos 持久化单实例 Sentinel 阈值，订单可启用单独的 Token Server 实施全局下单配额。订单、库存、支付可独立扩容 Outbox 转发线程，并用交易巡检观察积压与滞留。[稳定性配置与演练](../../infra/stability/README.md)
