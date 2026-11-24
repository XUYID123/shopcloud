# 服务端购物车接口

2026-11-21，ROUTE-05。按[商品与购物车契约](../../03-接口文档.md#3-商品与购物车)实现列表、设置数量和删除。OpenAPI、业务数据设计和开发顺序都指定 `shop-order`，因此使用 `flour_order` 及现有订单服务，更正购物车网关路由。

| 项目 | 决定 |
| --- | --- |
| MySQL | REQUIRED。订单库增量迁移创建 `cart_owner`、`cart_item`。按用户主键串行化写入，先锁所有者再查询和写入 `(user_id, sku_id)`，限制同一用户 20 种。绝对数量 PUT 重放不累加，DELETE 缺失也成功。 |
| 参数及权限 | REQUIRED。复用网关的 `cart:write:own`，给 ADMIN 补同一权限。用户 ID 只取订单服务内验签的网关主体。数量为 JSON 整数 1–100，SKU ID 为十进制正整数。 |
| 性能与一致性 | REQUIRED。列表一次主键范围查询，之后向商品服务批量查最多 20 条公开可售规格。商品查询沿用公开 SKU 可见性条件及商品主键，无跨库查询或远程调用持锁。购物车持久化只保存 SKU 和数量。已有失效项保留，展示字段置空。PUT 对不可售 SKU 返回 404。商品状态和报价仍由后续结算复核。 |
| 异常及日志 | REQUIRED。复用 Result、BusinessException、BizLog、Trace。超过种数返回 409，商品或数据库暂时不可用返回 503。只记录业务 ID 和结果。 |
| 内部鉴权 | REQUIRED。订单服务使用独立于地址查询的私钥，并限定接收服务、scope、方法、路径和原始查询串。批量接口默认关闭，启用时只在商品服务配置对应公钥。 |
| Redis、RocketMQ | NOT_REQUIRED。沿用网关权限 Redis，无购物车缓存和事件。购物车写入使用本地事务，失败可用相同 PUT/DELETE 安全重试。 |
| 验证 | REQUIRED。隔离 MySQL、签名商品批量读取、网关角色与归属、严格校验、失效和改价、重复操作、并发上限、下游故障。 |

## 本机启用

1. 在仓库根目录运行 `java infra/security/GenerateInternalJwtKeys.java infra/local-secrets order-cart-product`，生成购物车专用签名密钥。
2. 让用户服务执行 Flyway V6，为 ADMIN 补全本人购物车权限。通过 [Enable-CartRule.ps1](../../../infra/security/Enable-CartRule.ps1) 发布两条购物车规则，沿用规则版本校验。已有管理员权限快照按现有 TTL 刷新。
3. 为商品服务设置 `SHOP_PRODUCT_CART_LOOKUP_ENABLED=true`。它从 `SHOP_ORDER_CART_SIGNING_PUBLIC_KEY_LOCATION` 读取公钥，默认位置是模块目录下的 `../infra/local-secrets/order-cart-product-public.pem`。
4. 从 `shop-order` 目录启动订单服务。默认 Compose 使用本机 3310 的 `flour_order`，Flyway V1 建立购物车表。订单服务从 `SHOP_ORDER_CART_SIGNING_PRIVATE_KEY_LOCATION` 读取私钥，默认位置是模块目录下的 `../infra/local-secrets/order-cart-product-private.pem`。正常通过 Nacos 发现商品服务，隔离联调可指定 `SHOP_ORDER_CART_PRODUCT_URL`。网关加载新路由后将 `/api/v1/cart/**` 转给 `shop-order`。

## 验证

- 使用隔离 MySQL 8.4.11 和两套 Redis 8.2.10。公共、用户、网关、商品回归分别通过 47、75、91、56 项。订单模块复跑 `mvn -o -pl shop-order -am -Dtest=CartIntegrationTests,ShopOrderApplicationTests -Dsurefire.failIfNoSpecifiedTests=false test`，6 项通过。
- 5 项购物车集成测试覆盖真实 MySQL 和 HTTP、签名及 Trace 透传、参数类型、身份隔离、绝对数量、重复删除、实时价格与失效条目。两个并发新增争抢第 20 个位置时，只有一个成功。商品依赖故障返回 503，原有数量保持，删除可继续执行。
- 真实网关、用户、订单和商品服务使用静态发现完成端到端测试。普通用户与仅有 ADMIN 角色的账号均可操作本人购物车，无权限、匿名和未签名直连均被拒绝。核对了数据库中的实际数量、商品改价与禁用后的列表、重复删除，以及订单业务日志和商品访问日志中的同一 Trace。临时进程、容器和密钥已清理。
- 静态检查、10 项质量脚本测试、OpenAPI 购物车契约、Compose 与发布脚本语法检查通过。
- 本机离线 Maven 缺少打包插件依赖，联调使用本轮编译的类和已缓存依赖启动。商城前端仍使用本地购物袋。结算报价与 Nacos 环境联调待推进。
