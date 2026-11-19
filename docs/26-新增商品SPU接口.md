# 新增商品 SPU 接口

日期：2026-10-31。实现 POST /api/v1/admin/products；只创建商品共用资料，初始未上架，不创建 SKU、价格、库存。沿用当前 POM、MyBatis、application.properties、双 Redis 和内部 JWT 信任链。

## 请求与响应

```http
POST http://localhost:8888/api/v1/admin/products
Authorization: Bearer <accessToken>
Content-Type: application/json

{"categoryId":"1","name":"麦香高筋面粉","brand":"麦香","glutenType":"HIGH","usageText":"制作面包","ingredients":"小麦","storage":"阴凉干燥处","shelfLifeDays":180,"imageUrl":"https://static.example.com/flour.jpg"}
```

九个字段均必填：分类 ID 为正数 Long 字符串；name≤100、brand≤50、usageText≤200、ingredients/storage≤500；glutenType=HIGH/MEDIUM/LOW；shelfLifeDays 为 1～1095 的整数；imageUrl≤2048，为带有效主机的绝对 HTTPS 地址，不接受账号凭据或片段。字符串先去首尾空白。当前检查 URL 结构，不下载图片或校验图片实际内容，也未配置上传域名白名单。

未知字段、数字冒充字符串、小数/字符串冒充整数，以及 productId/status/version/priceFen/库存等额外字段返回 400。前端不能设置用户身份；网关认证并验证 product:write 后签发 aud=shop-product 的短期内部 JWT，商品服务公共过滤器通过验证后才执行 Controller。不是只看客户端传来的角色名称。

成功 HTTP 201，Location=/api/v1/admin/products/{productId}；正文沿用 Result，data 包含请求的九个字段，以及 productId、status=OFF_SHELF、version=1、skus=[]，共十三个字段。ID 为字符串，traceId 与响应追踪头一致。Location 标识后台资源，其详情 GET 尚待实现；公开详情 GET /api/v1/products/{productId} 已在 29 文档实现，只展示已上架且分类启用的商品。

| 情况 | 响应 |
|---|---|
| 非法参数、未知字段、URL 非法、分类 ID 溢出 | 400 VALIDATION_ERROR |
| 分类不存在 | 404 CATEGORY_NOT_FOUND |
| 分类已禁用 | 409 CATEGORY_DISABLED |
| 外部 Token 缺失/无效 | 网关原有 401 |
| 无 product:write 权限 | 403 FORBIDDEN |
| 直连下游、内部凭据失效/受众或请求绑定错误 | 401 INVALID_INTERNAL_TOKEN |
| 匿名内部凭据用于创建 | 403 FORBIDDEN |
| 暂时性数据库故障 | 503 DEPENDENCY_UNAVAILABLE |
| SQL 或程序错误 | 全局 500，不泄露内部细节 |

## 入库与代码阅读

1. [ProductController](../shop-product/src/main/java/org/spring/shopproduct/controller/ProductController.java) 接收 [ProductCreateRequest](../shop-product/src/main/java/org/spring/shopproduct/dto/ProductCreateRequest.java)，复用严格 JSON 与 Bean Validation。
2. [ProductService](../shop-product/src/main/java/org/spring/shopproduct/service/ProductService.java) 开启本地事务，调用 CategoryMapper 的 SELECT ... FOR SHARE；分类不存在或禁用则抛业务异常。共享行锁保持至提交，分类并发禁用/删除须等待，多个商品创建可共享读取。
3. 显式映射至 [ProductEntity](../shop-product/src/main/java/org/spring/shopproduct/entity/ProductEntity.java)，通过 [ProductMapper.xml](../shop-product/src/main/resources/mapper/ProductMapper.xml) 参数绑定插入。SQL 固定 OFF_SHELF 和 version=1，MyBatis 回填自增 ID。没有读库再写缓存或跨服务调用。
4. 返回 [ProductCreatedVO](../shop-product/src/main/java/org/spring/shopproduct/vo/ProductCreatedVO.java)。写入不自动重试；未知结果不能盲目再次创建。当前未增加商品唯一名称约束或请求幂等记录，同名商品允许存在，重复提交可能生成多条商品。

新增 [V2__create_product_spu.sql](../shop-product/src/main/resources/db/migration/V2__create_product_spu.sql)，启动商品服务时 Flyway 自动迁移，保留 V1 校验和。表含 category_id 同库外键、分类/状态/ID 索引、筋度/状态/保质期/版本约束；图片只存 image_url。分类删除被外键限制。价格归后续 SKU，生产/到期日期归库存批次。本次仅初始化版本，后续修改接口必须携带 expectedVersion 实现原子比较更新。

## 权限规则与验证

启动 6381 后，从根目录执行：

```powershell
./infra/security/Enable-ProductRule.ps1
```

脚本在默认 shop:rbac:v1:rules 中用版本 CAS 追加 product-create 规则，要求 POST /api/v1/admin/products 具备 product:write，保留全部既有规则；同名规则存在则提示审查，不强行覆盖。不会给普通注册用户提升权限。

定向回归：mvn -o -ntp -pl shop-product -am package -Dspring.cloud.nacos.discovery.enabled=false。测试包含真实 MySQL/Flyway、默认状态/版本/空 SKU、Location/Trace、非法字段、分类不存在/禁用、内部 JWT 与用途范围、异常分类。仅清理自身随机分类及商品。

三服务联调复用 Test-CategoryFlow.ps1，加入普通用户 403、匿名 401、管理员新增商品 201、强行设置状态 400、直连 401。使用临时端口/命名空间/账号，结束后先删除自己的 SPU 再删除分类，并关闭自身启动的服务。

实际结果：2026-10-31 定向构建 BUILD SUCCESS，common 35 项、product 18 项，共 53 项测试，失败/错误/跳过均为 0。V2 已在本地 MySQL 商品库迁移完成，商品 JAR 已重新生成。真实三服务 HTTP 联调上述五项商品检查及原分类/登录流程全部通过；临时数据和进程已清理。默认命名空间的商品创建规则已通过 CAS 追加，保留既有规则和用户权限。

记录：[定向构建日志](../target/product-create-tests.log)、[三服务联调报告](../target/category-smoke/result.json)。本地原 Docker 引擎未运行，启动 Docker Desktop 及既有 MySQL/双 Redis 容器后完成验证；保留原数据卷。联调关闭 Nacos 注册并使用 SimpleDiscoveryClient，未验证线上 Nacos 集群或生产容量；本次没有启动常驻业务服务。
