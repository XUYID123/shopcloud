# 公开 SKU 详情

2026-11-20。ROUTE-05，授权范围为补齐现有 OpenAPI 的 GET /api/v1/skus/{skuId}。短流程涉及商品查询和网关匿名规则。

## 需求与方案

SKU 创建接口和持久化已存在。公开详情复用现有 Sku 响应契约，只有启用 SKU、已上架商品和启用分类同时成立时可见。库存未接入，estimatedAvailable 返回 null。非法输入返回 VALIDATION_ERROR，不可见和不存在统一返回 SKU_NOT_FOUND。

| 评估 | 决策 |
|---|---|
| MySQL | REQUIRED。商品库一条只读 SQL 从 SKU 主键定位，按现有商品和分类主键连接。预期各表按 PRIMARY 定位，一条语句内判断可见性。无需迁移、锁、额外事务或回滚脚本。 |
| Redis | NOT_REQUIRED。读取当前 SKU 状态和价格，无新增键、TTL、回填或失效路径。沿用网关现有防刷。 |
| RocketMQ | NOT_REQUIRED。查询无事件、写入或恢复任务。 |
| 日志 | REQUIRED。复用两端 HTTP_ACCESS、商品 SKU_DETAIL 业务日志和 Trace ID。业务日志 ID 取成功结果，非法原始输入不进入 BizLog。 |
| 异常与参数 | REQUIRED。复用 Result、公共异常处理、正整数 ID 和 Long 上界规则。数据库暂时故障返回 503，程序错误走现有 500 处理。 |
| 权限与安全 | REQUIRED。仅放行 GET 的单段 SKU 详情，保留内部 JWT 的受众、路径和方法绑定。SQL 参数绑定、响应字段沿用 SKU DTO。 |
| 性能与并发 | REQUIRED。单主键查询，采用普通一致性读。只读 GET 可安全重复，无业务写竞争或幂等键。 |
| 测试 | REQUIRED。验证正常、隐藏状态、输入错误、内部 JWT、匿名网关和日志。 |

## 方案审查

采用同商品库主键读取，沿用 ADR-0003 的写事务边界。公共商品详情缓存继续按 ADR-0001 管理，独立 SKU 查询按现价直读。查询无消息投递、重试副作用或依赖恢复状态。

## 实现

请求由 [PublicSkuController](../../../shop-product/src/main/java/org/spring/shopproduct/controller/PublicSkuController.java) 交给 [SkuService](../../../shop-product/src/main/java/org/spring/shopproduct/service/SkuService.java)。[SkuMapper](../../../shop-product/src/main/resources/mapper/SkuMapper.xml) 用同一查询判断 SKU、SPU 和分类的可见性。网关及商品服务约束匿名访问方法与路径。发布时先更新商品服务，再更新网关白名单。

```http
GET http://localhost:8888/api/v1/skus/123
```

公开查询沿用 Result 和 Sku 字段，skuId 和 productId 是字符串，priceFen 单位为分，weightGram 单位为克。测试用例和响应与 [OpenAPI](../../openapi.json) 对应。

## 验证与代码审查

- `python -X utf8 infra/quality/gate.py lint`、PowerShell 冒烟脚本语法检查和 `git diff --check` 通过。
- 在本地商品 MySQL 上执行 `EXPLAIN`。SKU、SPU、分类均为 `type=const`、`key=PRIMARY`、估算各读取 1 行。查询计划使用的临时测试数据已回滚。
- Maven 使用仓库已有的 Spring Boot 4 离线缓存执行 `mvn -o -ntp -Dmaven.repo.local=target/harness-maven-upgrade -pl shop-product,shop-gateway,shop-user -am verify -Dspring.cloud.nacos.discovery.enabled=false`。common 45 项、user 62 项、gateway 91 项、product 52 项，共 250 项通过，无失败或跳过。覆盖最新售价、四种不可见状态、非法 ID、内部 JWT 边界以及模拟的暂时性数据库异常。
- `infra/security/Test-CategoryFlow.ps1` 使用新打包的网关、用户和商品服务及本地 MySQL、Redis，结果为 `passed=true`。匿名 GET 返回 200，非法 ID 返回 400，直连商品服务返回 401，下架后返回 404。额外路径和匿名 POST 仍被网关拒绝。响应头、统一响应、网关和商品两端的 `HTTP_ACCESS` 以及商品服务的 `SKU_DETAIL` 使用同一 Trace ID。结果保存在 `target/category-smoke/result.json`。
- 审查检查了方法与路径白名单、内部 JWT 约束、查询参数绑定、字段暴露、单语句一致性、数据库异常分类及原有 SKU 创建路径，未发现阻断问题。

## 交付

商品服务新增公开 Controller 和查询，common 提供 `SKU_NOT_FOUND`，网关和商品配置共同收敛匿名路径。接口说明、OpenAPI、自动化测试和网关冒烟脚本已更新。发布产物由 Maven `verify` 打包。验证使用本地静态服务发现，未验证 Nacos 部署或压测，503 分支由单测模拟数据库异常。
