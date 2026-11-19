# 当前业务接口

- 用户注册 POST /api/v1/users；登录、刷新和退出 POST /api/auth/{login|refresh|logout}；GET /internal/v1/users/{userId}/permissions 提供内部权限快照。用户 schema 管理账号和授权关联，Redis 存刷新及权限缓存。见[注册](../09-用户注册实现与验收.md)、[身份](../20-双Token登录刷新与JJWT.md)。
- 分类读取 GET /api/v1/categories；后台创建 POST /api/v1/admin/categories，数据库保证分类名唯一。见[分类读](../25-商品分类列表查询.md)、[分类写](../23-商品分类创建接口.md)。
- 后台 POST /api/v1/admin/products 新建未上架 SPU、POST /api/v1/admin/skus 新建 SKU、PUT /api/v1/admin/products/{productId}/sale-status 版本化上下架。见[SPU](../26-新增商品SPU接口.md)、[SKU](../27-新增商品SKU接口.md)、[销售态](../28-商品上架与下架接口.md)。
- 公开 GET /api/v1/products 分页和 GET /api/v1/products/{productId} 详情，详情使用 Redis 缓存和 MySQL 回源。见[Controller](../../shop-product/src/main/java/org/spring/shopproduct/controller/PublicProductController.java)和[缓存](../32-商品详情Redis缓存与失效.md)。
- cart/order/pay 的交易业务为骨架，库存扣减、支付、订单异步、通用商品编辑与角色管理待实现。shop-order 可按配置初始化消息 Producer，交易事件需随实际用例接入。以当前源码核实 [OpenAPI](../openapi.json) 中的规划契约。
