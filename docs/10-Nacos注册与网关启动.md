# 当前服务注册与网关启动

修订日期：2026-10-18。本文描述当前 JDK 17 / Spring Boot 3.2.5 / Spring Cloud 2023.0.1 / Alibaba 2023.0.1.0 的代码配置。未来架构版本以 01/05 文档为设计目标，不能混用依赖。

## 配置与 POM 位置

- user、gateway 保留现有 `application.yml`，其余四个服务保留 `application.properties`，每个服务仍拥有自己的启动配置。
- 五个业务服务继承根 POM，默认获得 MyBatis/MySQL/Flyway；gateway/common 继承基础父 POM 隔离数据库。基础父 POM 统一 Boot parent、Cloud/Alibaba BOM 和插件，原有模块坐标保留，详情见 [POM 继承与依赖管理说明](13-POM继承与依赖管理说明.md)。
- 根 POM 的 `modules` 登记全部模块以统一构建；聚合与继承分别由 modules、parent 表达，当前两者均已配置。
- 四个原先只有基础 starter 的服务改用 Web starter，拥有 HTTP 服务端口，才能作为可持续运行的业务服务注册到 Nacos。
- product/cart/order/pay 尚未接入自己的数据库，保留默认继承的数据库依赖，但在各自 properties 中暂时排除 DataSource/Flyway/MyBatis 自动装配；实际接库时删除这些排除配置。
- common 不添加 Nacos starter，不作为独立服务启动，也不加入网关的服务发现名单。

## 服务与端口

| 模块 | Nacos 服务名 | 端口 | 配置文件 |
|---|---|---|---|
| shop-user | shop-user | 8081 | application.yml |
| shop-product | shop-product | 8082 | application.properties |
| shop-cart | shop-cart | 8083 | application.properties |
| shop-order | shop-order | 8084 | application.properties |
| shop-pay | shop-pay | 8085 | application.properties |
| shop-gateway | gateway-service | 8888 | application.yml |

默认 Nacos 地址为 `localhost:8848`，客户端还会连接 `9848` gRPC 端口。各服务使用 `public` namespace 和 `DEFAULT_GROUP`。四个新增服务与网关支持环境变量 `NACOS_SERVER_ADDR`；user 可在其原有 YAML 中设置地址。当前只接入服务注册发现，没有把本地应用配置迁入 Nacos Config。

Nacos 控制台位于 `http://localhost:8080/`。控制台登录与客户端注册鉴权是不同开关；当前本地 Nacos 的客户端注册鉴权关闭，因此不在项目中写入管理员密码。如果服务端开启客户端鉴权，需要为各服务提供对应的 discovery username/password 配置。

## IDEA 启动

1. Maven 面板重新加载全部项目。
2. 确认 Nacos 正常运行，Docker Desktop 可用。
3. 启动 `ShopUserApplication`，工作目录继续设置为 `shop-user`，让它使用自己的 Compose 文件启动 MySQL。
4. 分别启动 `ShopProductApplication`、`ShopCartApplication`、`ShopOrderApplication`、`ShopPayApplication`。
5. 启动 `ShopGatewayApplication`。
6. 在 Nacos 控制台的 `public / DEFAULT_GROUP` 下查看五个业务服务及 `gateway-service`，实例应健康。

注册名取自 `spring.application.name`，网关 `lb://` 后的名字必须与它完全一致。服务停止后，其临时实例会移除。

## 网关转发

入口统一使用 `http://localhost:8888`，通过 Nacos 服务发现和 LoadBalancer 选择业务实例。

| 路径 | 下游 |
|---|---|
| `/api/v1/users`、`/api/v1/users/**`、`/api/v1/auth/**`、`/api/v1/addresses/**`（含根路径） | shop-user |
| `/api/v1/products/**`、`/api/v1/categories/**`（含根路径）、`/api/v1/skus/**` 及对应商品管理路径 | shop-product |
| `/api/v1/cart/**`（含根路径） | shop-order |
| `/api/v1/orders/**`（含根路径）、`/api/v1/checkout/**`、订单管理路径 | shop-order |
| `/api/v1/payment-sessions/**`（含根路径）、`/callbacks/payments/**` | shop-pay |

上述 RESTful 路径原样转发。例如 `POST http://localhost:8888/api/v1/users` 会转发到用户服务的 `POST /api/v1/users`。原有测试入口 `/api/user/x` 保留；其 StripPrefix 当前被注释，因此也按原路径转发。

自动发现路由也保留：`/shop-product/路径` 转发到商品服务 `/路径`。`include-expression` 只允许五个明确的业务服务名，即使其他服务注册到同一 Nacos，也不会自动生成网关路由。common 和网关自身都被排除。

商品、库存入库和购物车的实际入口见[业务接口](business/README.md)。公开路径和权限由网关按服务路由管理，已实现的内部业务接口由调用服务直连。

## 验证方式

- 启动测试验证各模块可以加载 Spring 上下文；用户服务的已有检查还验证 MySQL / Flyway。
- `GatewayRoutingTests` 使用本机临时 HTTP 后端及测试注册表，验证五个服务的路由、请求路径/查询参数/方法/请求体、原有入口，以及 common / gateway 排除规则，不依赖外部 Nacos。
- 真实 Nacos 联调应检查每个服务的健康实例，再经网关调用已经实现的注册接口。临时测试进程应在验证完成后停止，避免占用 IDEA 启动端口。

2026-10-16 实际验证结果：七个模块的启动测试及三个新增网关转发测试共 10 项通过；真实 Nacos 中五个业务服务均有健康实例，端口分别为 8081～8085。独立验证网关通过服务发现转发至四个新增服务，并经 RESTful 入口访问用户注册接口，非法空请求返回用户服务的 `400 VALIDATION_ERROR`，没有创建账号。四个骨架服务的探测返回下游 404，且服务名前缀正确移除，证明路由到达了尚无业务接口的后端。common / gateway 自动路由排除检查通过。

本次保留用户已从 IDE 启动的 user 与 gateway 进程；临时启动的四个服务和独立验证网关在验证后停止。需要在 IDEA 重新加载 Maven、启动新增服务并重启原网关，才能让其使用最新配置。
