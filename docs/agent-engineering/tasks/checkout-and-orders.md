# 结算与订单

2026-11-22，ROUTE-05。按[接口文档](../../03-接口文档.md#4-结算与订单)交付结算报价，接通用户地址和商品权威报价。

## 结算报价方案

- MySQL REQUIRED。订单库增量 Flyway 建立 `checkout_quote`，单次 INSERT 原子持久化本人地址、商品快照、金额、规则版本、报价摘要和 5 分钟过期时间。HTTP 调用在写库前完成，不持远程调用事务。报价 ID 唯一，重复报价各自有效；未来下单在同一事务中锁报价并标记消费。
- 参数、权限、异常日志 REQUIRED。沿用 `order:create` 和已验签网关主体，ADMIN 补本人权限。校验 1–20 个不同 SKU、1–100 袋及严格 ID 类型。通过订单地址专用签名查询本人地址；商品报价使用另一独立密钥、固定方法路径及 scope。不可售 SKU 返回 422，地址缺失或越权返回 404，依赖故障返回 503。记录 Trace 和报价 ID。
- 性能及规则 REQUIRED。地址和商品各一次有界调用。商品以一条主键查询返回 SKU 与 SPU 的版本及可售信息。按已配置的上海地区白名单计算 FREIGHT_V1，最多 500 kg。摘要覆盖用户、地址、商品版本及数量和规则。公开响应不包含收货人等个人信息。
- Redis 与 RocketMQ 对报价 NOT_REQUIRED。报价不修改库存。订单交易所需的本地 Outbox、消费幂等与状态转换按交易阶段单独验证。

## 本机启用

1. 从仓库根目录依次运行 `java infra/security/GenerateInternalJwtKeys.java infra/local-secrets order-user` 和 `java infra/security/GenerateInternalJwtKeys.java infra/local-secrets order-quote-product`。生成器保留原有完整密钥对。
2. 在用户服务设置 `SHOP_USER_ORDER_ADDRESS_LOOKUP_ENABLED=true`，配置 `SHOP_ORDER_ADDRESS_SIGNING_PUBLIC_KEY_LOCATION`。在商品服务设置 `SHOP_PRODUCT_ORDER_QUOTE_ENABLED=true`，配置 `SHOP_ORDER_QUOTE_PRODUCT_PUBLIC_KEY_LOCATION`。按模块目录启动时，默认从 `../infra/local-secrets` 读取对应公钥。
3. 在订单服务设置 `SHOP_ORDER_CHECKOUT_ENABLED=true`，使用 `SHOP_ORDER_ADDRESS_SIGNING_PRIVATE_KEY_LOCATION` 和 `SHOP_ORDER_QUOTE_PRODUCT_PRIVATE_KEY_LOCATION` 指向两把独立私钥。默认配送地区是浦东新区 `310115`。可用 `SHOP_ORDER_CHECKOUT_REGION_CODES` 配置逗号分隔的上海区县白名单。服务默认通过 Nacos 发现下游，本机静态联调可用 `SHOP_ORDER_CHECKOUT_USER_URL` 和 `SHOP_ORDER_CHECKOUT_PRODUCT_URL`。
4. 用户服务 Flyway V7 为 ADMIN 补 `order:create`，订单服务 Flyway V2 创建报价表。把[示例规则](../../../infra/security/examples/rbac-rules.json)的 `checkout-quote` 合并到现有规则后，用 [Publish-DevRbac.ps1](../../../infra/security/Publish-DevRbac.ps1) 带当前版本发布。用户权限快照按原有 TTL 刷新。

## 验收

- 使用隔离 MySQL 8.4.11 和两套 Redis 8.2.10，执行 `mvn -o -pl shop-order,shop-product,shop-user,shop-gateway -am -Dtest=CheckoutQuoteServiceTests,CartIntegrationTests,ShopOrderApplicationTests,OrderAddressIntegrationTests,UserPermissionIntegrationTests,SkuIntegrationTests,GatewayRoutingTests,ScopedServiceJwtTests -Dsurefire.failIfNoSpecifiedTests=false test` 并对订单模块单独复跑。公共、用户、网关、商品、订单模块分别有 2、10、3、8、11 项测试通过。新增用例覆盖 20/30/500 kg 的运费边界、报价摘要稳定性、改版、金额超限、不可售及无效下游数据。
- 真实网关、订单、用户和商品联调通过。两袋 5 kg、每袋 3,200 分的应付金额为 7,400 分。核对 MySQL 已保存地址和商品版本快照、5 分钟有效期及金额。商品改价后新报价的摘要与价格同时更新；越权、无权限、参数错误、商品禁用、超重及不支持配送的请求均未写入报价。成功响应禁止缓存，跨服务 Trace 和报价编号可追踪。
- 静态检查和 OpenAPI 契约检查通过。联调使用静态服务发现，临时服务、容器及密钥已清理。报价等待后续下单流程复核并消费。
