# 本地快速启动

适用于 Windows PowerShell 和 IDEA。根据目的选择下面的链路。首次导入 Maven 项目时，选择 JDK 17 作为项目和运行配置的 SDK，Java 服务使用对应模块目录作为 Working directory。下面的终端命令均在仓库根目录执行。

| 目的 | 最低依赖 | 成功标准 |
| --- | --- | --- |
| 预览商城页面和静态资源 | Node.js、`shop-web` | 首页返回 HTTP 200 并显示面粉商城界面 |
| 浏览真实商品目录 | 上述前端、网关、商品服务、Nacos、商品 MySQL、两套 Redis | 前端代理的分类和商品列表返回 HTTP 200、`code=OK` |
| 开发或调试商品后台 | 网关、商品、用户服务、Nacos、两套 MySQL 和两套 Redis | 商品查询通过网关返回 200。使用已有合适权限的本地账号能登录并调试授权链 |

Node 脚本没有第三方 npm 依赖。本轮 Node 22.18.0 通过前端检查。Java 服务本机启动需要 Maven 3.9+。使用仓库 Compose 时，需要 Docker 引擎和 Compose v2。上述三个路径都可以独立于库存、订单、支付和 RocketMQ 运行。联调库存入库见[库存批次入库记录](../agent-engineering/tasks/inventory-batch-receipt.md)，联调后端购物车见[购物车启用说明](../agent-engineering/tasks/cart-items.md#本机启用)。

## 配置与端口

| 组件 | 默认端口 | 配置来源 |
| --- | --- | --- |
| `shop-web` | HTTP `127.0.0.1:5173` | [前端服务](../../shop-web/server.mjs)的 `SHOP_WEB_PORT` 和 `SHOP_WEB_GATEWAY_URL`，上游默认 `http://127.0.0.1:8888` |
| `gateway-service` | HTTP `8888` | [应用配置](../../shop-gateway/src/main/resources/application.yml) |
| `shop-product` | HTTP `8082`，商品 MySQL `127.0.0.1:3308` | [应用配置](../../shop-product/src/main/resources/application.properties)和[商品 Compose](../../shop-product/compose.yaml) |
| `shop-user` | HTTP `8081`，用户 MySQL `127.0.0.1:3307` | [应用配置](../../shop-user/src/main/resources/application.yml)和[用户 Compose](../../shop-user/compose.yaml) |
| 安全 Redis、业务 Redis | `127.0.0.1:6380`、`127.0.0.1:6381` | [网关 Compose](../../shop-gateway/compose.yaml)。前者供防刷和刷新会话，后者供权限及商品缓存 |
| Nacos | `localhost:8848`，客户端还连接 `9848` | 使用已运行的本地实例。网关和商品支持 `NACOS_SERVER_ADDR`，用户的默认值来自其 YAML |

MySQL 初始化和 Flyway 由各应用的本机配置管理。依照应用与 Compose 中的变量名提供本地配置。已有环境须复用对应的开发 JWT 密钥对。新建本地环境可使用仓库已有的工具，密钥保存在 Git 忽略的目录。参见[登录 JWT](../20-双Token登录刷新与JJWT.md)及[内部 JWT](../24-网关内部JWT与下游统一验签.md)。

```powershell
java infra/security/GenerateDevJwtKeys.java
java infra/security/GenerateInternalJwtKeys.java
docker compose -f shop-gateway/compose.yaml up -d redis-gateway redis-cache
```

## 路径 A · 商城浏览

1. 只看页面时，直接启动前端。需要查看真实目录时，先准备 Nacos、Redis 和开发密钥，再用 IDEA 启动 `ShopProductApplication`，工作目录为 `shop-product`。默认由该模块的 Compose 启动 MySQL，等 Flyway 成功并在 Nacos 的 `DEFAULT_GROUP` 中看到健康的 `shop-product` 实例。
2. 需要真实目录时，接着启动 `ShopGatewayApplication`，工作目录为 `shop-gateway`。在启动前端前先用 8888 的分类接口确认网关已能到达商品服务。
3. 启动前端并验证。仅预览时验证首页即可，真实目录再检查两个 API。空目录可能合法，`data.items` 为空且 `code=OK` 表示 API 正常。

```powershell
npm.cmd --prefix shop-web run dev
```

在另一终端执行：

```powershell
curl.exe --fail --silent --show-error --output NUL --write-out "%{http_code}\n" "http://127.0.0.1:5173/"
curl.exe --fail --silent --show-error "http://127.0.0.1:8888/api/v1/categories"
curl.exe --fail --silent --show-error "http://127.0.0.1:5173/api/v1/products?page=1&size=10"
```

## 路径 B · 商品开发

1. 准备 Nacos、双 Redis 和两套开发密钥。依次启动 `ShopUserApplication`、`ShopProductApplication`、`ShopGatewayApplication`。在 IDEA 中分别使用 `shop-user`、`shop-product`、`shop-gateway` 作为工作目录，等待两套 MySQL 的 Flyway 迁移完成。确认 Nacos 中有健康的 `shop-user`、`shop-product` 实例。
2. 用下面的只读请求确认链路。公开目录应为 200 且 `code=OK`。需调试后台接口时，通过网关使用已有的本地开发账号，并核对目标操作的动态权限。接口、权限和请求字段见 [OpenAPI](../openapi.json)及[商品接口索引](../business/README.md)。如果只开发后端，可以跳过 `shop-web`。
3. 运行快速质量检查，或根据任务记录选用[全量检查](../agent-engineering/quality-gate.md)。快速检查覆盖静态门禁和无需运行中间件的 Maven 测试。

```powershell
curl.exe --fail --silent --show-error "http://127.0.0.1:8888/api/v1/products?page=1&size=10"
python -X utf8 infra/quality/gate.py quick
```

## CRM 目录本机预览

商品服务与前端进程共享 `SHOP_PRODUCT_CRM_PREVIEW_TOKEN`，其值应为随机的 64 个小写十六进制字符。在同一 PowerShell 会话生成环境变量，再从该会话启动进程，或将变量分别设在 IDEA 的本机启动配置中。

```powershell
$env:SHOP_PRODUCT_CRM_PREVIEW_TOKEN = node -p "require('node:crypto').randomBytes(32).toString('hex')"
$env:SHOP_PRODUCT_CRM_PREVIEW_ENABLED = 'true'
```

商品服务和网关可用启动参数 `--server.address=127.0.0.1` 监听回环地址。前端默认监听回环地址并连接本机网关。本机无 Nacos 时，为商品服务和网关加上 `--spring.cloud.nacos.discovery.enabled=false`，网关再加上 `--spring.cloud.discovery.client.simple.instances.shop-product[0].uri=http://127.0.0.1:8082`。

启动新版商品服务完成 Flyway V5 后，设置本机 MySQL 凭据环境变量 `MYSQL_PWD`，在仓库根目录运行下列命令并打开 [本机 CRM 商品目录](http://127.0.0.1:5173/crm-catalog)。

```powershell
python -X utf8 infra/catalog/crm_catalog.py import shop-web/catalog-review.json
```

导入脚本重新读取数据库并逐字段比较，重复导入同一份文件使用同一条快照记录。数据文件含当前 CRM 账号可见的报价，仅供本机使用。

## 本轮验证 · 2026-11-20

- `npm.cmd --prefix shop-web run check` 的 5 项检查通过。使用内存请求和上游替身覆盖页面、代理、登录、网关故障和 CRM 预览访问边界。
- 本机以静态服务发现验证前端、网关、商品服务和 MySQL 的 CRM 目录链路。Nacos 配置参考[既有启动记录](../10-Nacos注册与网关启动.md)，本轮验收结果见[CRM 导入记录](../agent-engineering/tasks/shop-web-catalog-import.md)。
