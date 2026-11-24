# 认证、鉴权与数据安全

[网关权限](../17-Redis权限冷加载与OpenFeign.md)、[双 Token](../20-双Token登录刷新与JJWT.md)和[内部 JWT](../24-网关内部JWT与下游统一验签.md)构成信任链。接口评估匿名规则、方法与路径权限、受众、请求绑定和网关头清洗。Service 以签名主体及资源 ID 限定资源归属，后台操作遵守权限和资源范围。

订单地址查询使用独立的订单服务 RSA 公钥，验签限定 `shop-order`、受众 `shop-user`、`order:address:read`、GET、路径及原始查询串。查询同时按用户和地址编号匹配归属，收件信息响应禁止缓存。[收货地址记录](../agent-engineering/tasks/create-shipping-address.md)

购物车入口要求 `cart:write:own`，订单服务从已验签的网关主体取得用户编号。商品快照读取使用另一对密钥，令牌限定 `shop-order`、受众 `shop-product`、`product:cart-metadata:read`、GET、路径及原始 SKU 查询串。[购物车接口](../agent-engineering/tasks/cart-items.md)

履约接口由网关分别校验 `order:read:any`、`order:ship` 或 `order:read:own`。确认收货在订单库校验订单归属。订单服务读取批次分配时使用限定 `shop-order`、受众 `shop-inventory`、`order:inventory:read`、GET 和具体路径的独立密钥。[M5 启用与验收](../agent-engineering/tasks/m5-fulfillment.md)

使用 DTO、参数绑定及业务允许列表。错误响应复用公共错误码；日志、缓存和消息使用最少数据。测试覆盖匿名、过期/受众不符、越权资源、字段越权及权限 Redis 故障。使用隔离环境和测试夹具。未来消息流程审查生产者验证、Topic/Group ACL、消息体与版本校验、防重复副作用和审计。
