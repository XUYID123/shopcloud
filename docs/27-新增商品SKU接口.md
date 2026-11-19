# 新增商品 SKU 接口

实现日期：2026-11-01。新增规格、定价和启用状态；库存初始化与库存管理后续实现。

## 请求与返回

`POST http://localhost:8888/api/v1/admin/skus`，携带 `Authorization: Bearer <Access Token>`，需要 `product:write` 权限。

```json
{
  "productId": "10001",
  "skuCode": "FLOUR-HIGH-5KG",
  "specName": "5kg袋装",
  "weightGram": 5000,
  "priceFen": 2990,
  "enabled": true
}
```

| 字段 | 校验和含义 |
|---|---|
| productId | 必填正整数 ID 字符串，不超过 Long.MAX_VALUE；SPU 必须存在 |
| skuCode | 必填，1～50 个 ASCII 字母、数字、下划线或连字符；全局唯一，不区分大小写 |
| specName | 必填，1～100 字符，去首尾空白后不可为空 |
| weightGram | 必填整数，100～50000，单位克 |
| priceFen | 必填整数，1～10000000，单位分 |
| enabled | 必填 JSON 布尔值，允许 false；不能传字符串 |

字符串去首尾空白；编码保留输入大小写。禁止额外字段及标量强制转换，例如 version、skuId、stock、estimatedAvailable、角色和身份字段均不能写入请求体。

成功返回 HTTP 201，`Location: /api/v1/admin/skus/{skuId}`。Location 是资源标识，详情 GET 尚未实现。

```json
{
  "code": "OK",
  "message": "操作成功",
  "data": {
    "skuId": "2001",
    "productId": "10001",
    "skuCode": "FLOUR-HIGH-5KG",
    "specName": "5kg袋装",
    "weightGram": 5000,
    "priceFen": 2990,
    "enabled": true,
    "estimatedAvailable": null,
    "version": 1
  },
  "traceId": "本次请求关联号"
}
```

`estimatedAvailable=null` 表示没有接入库存估算，不是可售库存为零。创建规格不自动上架 SPU；下架 SPU 可以提前创建规格。

## 代码执行顺序

1. 入口整理、Sentinel 容量限流、6380 Redis 按 IP 防刷后，网关验证 Access JWT 和 RBAC；新增规则要求 `product:write`。
2. 网关为 shop-product 签发请求绑定的内部 JWT，公共 Servlet 过滤器验证签名、aud、过期时间和请求绑定。
3. [SkuController](../shop-product/src/main/java/org/spring/shopproduct/controller/SkuController.java) 校验六字段 DTO，调用服务；不重复解析身份头。
4. [SkuService](../shop-product/src/main/java/org/spring/shopproduct/service/SkuService.java) 开启本地事务，将 ID 解析为 Long，并执行 `SELECT id FROM product_spu WHERE id=? FOR SHARE`。
5. SPU 不存在返回 404；存在时共享锁保持到提交，防止检查后被并发删除。持锁期间只执行同库操作。
6. [SkuMapper.xml](../shop-product/src/main/resources/mapper/SkuMapper.xml) 参数绑定插入六个字段，固定 version=1，数据库生成 ID 和时间。
7. `uk_sku_code` 唯一索引原子判定冲突，覆盖多实例并发；不采用先查编码再插入的方式。事务提交成功后返回 201。

表结构见 [V3 迁移](../shop-product/src/main/resources/db/migration/V3__create_product_sku.sql)：product_sku 通过 product_id 关联 product_spu；金额/重量使用 BIGINT，包含唯一索引、父商品外键、范围 CHECK、创建和更新时间。

| 场景 | HTTP / Result.code |
|---|---|
| 参数非法、额外字段、ID 溢出 | 400 / VALIDATION_ERROR |
| 网关缺少或无效 Access Token | 401 / 沿用网关认证错误码 |
| 直接访问缺少有效内部凭据 | 401 / INVALID_INTERNAL_TOKEN |
| 缺少 product:write 或内部匿名凭据访问管理接口 | 403 / FORBIDDEN |
| 所属商品不存在 | 404 / PRODUCT_NOT_FOUND |
| 编码重复，包括大小写变化 | 409 / SKU_CODE_EXISTS |
| 网关容量或 IP 防刷拒绝 | 429 / 沿用网关拒绝码 |
| 暂时性数据库/权限/防刷依赖故障 | 503 / 对应依赖不可用码 |

SQL 程序错误继续交由全局异常处理返回 500。写入不自动重试，不通过吞异常伪造成功。连接中断导致结果不明确时可人工按 skuCode 查库；本次未实现结果重放型幂等，相同编码再次提交返回 409。

## 权限规则与验证

开发环境使用 `infra/security/Enable-SkuRule.ps1` 向 6381 业务 Redis 发布 `sku-create` 规则。脚本保留其他规则并使用版本 CAS；如果同名规则存在，不覆盖其内容。它不授予任何用户权限；ADMIN 的默认 product:write 沿用已有用户库数据。

```powershell
./infra/security/Enable-SkuRule.ps1
```

POM、配置文件组织、Redis 职责和既有网关认证/限流代码均保持原结构。

测试覆盖：真实 MySQL/Flyway 入库与返回字段、参数上下界、未知字段、父商品缺失、大小写编码冲突、12 并发同编码恰好一个 201 和十一个 409、内部 JWT 防绕过；服务层验证故障 503、SQL 程序错误不被掩盖、写入不自动重试。

端到端脚本复用 [Test-CategoryFlow.ps1](../infra/security/Test-CategoryFlow.ps1)：真实网关/用户/商品进程，验证管理员 201、普通用户 403、匿名 401、重复编码 409、直接访问 401，以及 Trace ID/Location；使用独立规则命名空间和临时账号，按外键顺序清理自身数据。

2026-11-01 实际验证记录：

- 初次执行因 Docker 未运行，common 的真实 Redis 测试失败；启动现有 Compose 依赖后重新完整运行。
- 执行 `mvn -o -ntp -pl shop-product -am package -Dspring.cloud.nacos.discovery.enabled=false`：BUILD SUCCESS，common 35 项、product 24 项，共 59 项全部通过，无失败/错误/跳过；包括新加的 6 项 SKU 测试。
- V3 已应用于本地 flour_product，商品可执行 JAR 已重新打包。
- 真实 MySQL 12 并发同编码：恰好 1 个 201、11 个 SKU_CODE_EXISTS/409。
- 执行 `./infra/security/Test-CategoryFlow.ps1 -JavaHome D:/developjava/Java/jdk-17`：报告 passed=true，SKU 管理员 201、普通用户 403、匿名 401、重复 409、直连 401 全部通过，原分类/SPU 检查也通过。
- 已执行 `Enable-SkuRule.ps1` 发布本地默认命名空间的 SKU 权限规则；没有给现有用户修改角色。
- 临时进程及账号/规格/商品/分类/权限命名空间已清理；Docker 依赖继续运行。本次未启动常驻业务服务，IDE 启动或重启商品服务后加载新代码。

日志位于 `target/sku-create-tests.log`，端到端报告位于 `target/category-smoke/result.json`。脚本使用 SimpleDiscoveryClient 验证实际 HTTP 转发，不代表 Nacos 集群或生产压测验收；未运行所有微服务的完整回归测试。
