# 当前业务接口

- 用户注册 POST /api/v1/users；登录、刷新和退出 POST /api/auth/{login|refresh|logout}；GET /internal/v1/users/{userId}/permissions 提供内部权限快照。用户 schema 管理账号和授权关联，Redis 存刷新及权限缓存。见[注册](../09-用户注册实现与验收.md)、[身份](../20-双Token登录刷新与JJWT.md)。
- 分类读取 GET /api/v1/categories；后台创建 POST /api/v1/admin/categories，数据库保证分类名唯一。见[分类读](../25-商品分类列表查询.md)、[分类写](../23-商品分类创建接口.md)。
- 收货地址操作限本人。GET、POST /api/v1/addresses 提供列表与新增，PUT、DELETE /api/v1/addresses/{addressId} 提供完整替换与删除。内部 GET /internal/v1/users/addresses/{addressId}?userId=... 为订单服务校验地址归属并读取收件信息，默认关闭。[地址接口记录](../agent-engineering/tasks/create-shipping-address.md)
- 后台 POST /api/v1/admin/products 新建未上架 SPU、POST /api/v1/admin/skus 新建 SKU、PUT /api/v1/admin/products/{productId}/sale-status 版本化上下架。见[SPU](../26-新增商品SPU接口.md)、[SKU](../27-新增商品SKU接口.md)、[销售态](../28-商品上架与下架接口.md)。
- 公开 GET /api/v1/products 分页和 GET /api/v1/products/{productId} 详情，详情使用 Redis 缓存和 MySQL 回源。见[Controller](../../shop-product/src/main/java/org/spring/shopproduct/controller/PublicProductController.java)和[缓存](../32-商品详情Redis缓存与失效.md)。
- 公开 GET /api/v1/skus/{skuId} 从商品库读取可见规格及当前价格，返回的 estimatedAvailable 为 null。见[接口交付记录](../agent-engineering/tasks/public-sku-detail.md)。
- 本机 CRM 预览用 GET /api/v1/products/crm-catalog 分页，GET /api/v1/products/crm-catalog/overview 提供分类总览。两个接口需预览令牌，默认关闭。[参数与验收](../agent-engineering/tasks/shop-web-catalog-import.md#接口与验收)
- 后台 POST /api/v1/admin/inventory/batches 已实现库存批次入库，事务内写批次、汇总与流水，支持持久化幂等。见[库存入库记录](../agent-engineering/tasks/inventory-batch-receipt.md)。
- 入库时，库存服务调用商品服务的内部 GET /internal/v1/products/skus/{skuId}/stock-metadata 读取 SKU 保质期。该接口使用库存服务的独立凭据，可随入库能力启用。[库存入库记录](../agent-engineering/tasks/inventory-batch-receipt.md)
- 购物车由 shop-order 提供。GET /api/v1/cart/items 查看本人购物车，PUT、DELETE /api/v1/cart/items/{skuId} 设置绝对数量和移除规格。商品失效时保留该条目，价格与可售状态实时读取。[购物车记录](../agent-engineering/tasks/cart-items.md)
- 结算报价 POST /api/v1/checkout/quotes 校验本人地址并实时复核 SKU，通过商品服务内部 POST /internal/v1/products/quotes 读取价格和版本。报价按 FREIGHT_V1 算费并持久化 5 分钟。[结算报价记录](../agent-engineering/tasks/checkout-and-orders.md)
- 下单 POST /api/v1/orders 复核报价并异步预占库存。GET /api/v1/orders 和 GET /api/v1/orders/{orderNo} 查询本人订单。库存提供内部 GET /internal/v1/inventory/reservations/{orderNo} 和 GET /internal/v1/inventory/reservations/{orderNo}/allocation。[M3 实现记录](../agent-engineering/tasks/m3-order-reservation.md)
- M4 支持 Mock 支付单、签名回调、开发环境支付事件、订单取消与关单、库存确认或释放，以及异常全额退款。用户可查询支付单和取消结果。[M4 实现记录](../agent-engineering/tasks/m4-payment-close.md)
- M5 支持后台订单分页与详情、发货与批次追溯、买家确认收货。[M5 实现记录](../agent-engineering/tasks/m5-fulfillment.md)
- 真实支付渠道、通用商品编辑及角色管理待实现。以当前源码核实 [OpenAPI](../openapi.json) 中的规划契约。
