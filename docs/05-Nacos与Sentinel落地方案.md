# Nacos 与 Sentinel 落地方案

修订日期：2026-10-14。Nacos 与 Sentinel 是本项目第一版的必选组件，本文件是实施设计，示例尚未在当前应用中执行。

## 1. 固定职责与版本

| 组件 | 版本 | 职责 |
|---|---|---|
| Spring Boot | 4.0.8 | 应用框架，统一依赖与构建插件 |
| Spring Cloud | 2025.1.3 | Gateway、LoadBalancer、HTTP 服务调用 |
| Spring Cloud Alibaba | 2025.1.0.0 | Nacos Discovery/Config、Sentinel Spring 与网关适配 |
| Nacos Server | 3.2.4 | 服务注册发现、集中配置、Sentinel 规则存储 |
| Nacos Client | 3.1.1，由 Alibaba BOM 管理 | Java 应用连接服务端；与服务端独立版本维护 |
| Sentinel Client / Dashboard | 1.8.9 | 应用内规则执行、监控控制台；多实例精确流控另外部署 Token Server |

Alibaba 官方的 2025.1 分支适配 Boot 4.0.x / Cloud 2025.1.x；本方案维护补丁组合要在 M0 完成依赖解析及真实集成验证，不能直接沿用 Boot 4.1.1 骨架推定兼容。[版本矩阵](https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/)、[发布说明](https://github.com/alibaba/spring-cloud-alibaba/releases)。Nacos Server 3.2.4 的版本依据为[发布历史](https://nacos.io/en/download/release-history/)。

Nacos 存注册数据和配置，Sentinel 执行规则；Outbox/RabbitMQ 负责业务消息，MySQL 负责交易一致性。Sentinel 熔断不能代替订单/支付补偿，Nacos 配置也不存用户订单。

## 2. 工程依赖

父 POM 导入 `spring-boot-dependencies:4.0.8`、`spring-cloud-dependencies:2025.1.3`、`com.alibaba.cloud:spring-cloud-alibaba-dependencies:2025.1.0.0`，显式统一 Maven 编译和 Boot 构建插件版本。不要让某些模块使用独立 4.1.1 parent。

网关和五个业务服务都引入以下依赖；版本交给 BOM，不在模块重复写版本：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-datasource-nacos</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

网关另外引入 `org.springframework.cloud:spring-cloud-starter-gateway-server-webflux` 与 `com.alibaba.cloud:spring-cloud-alibaba-sentinel-gateway`，使用当前分支适配器，不复制旧教程的 Gateway adapter 版本。网关不引入 MVC starter；业务服务使用 MVC starter，避免 WebFlux/MVC 混装。[Gateway starter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/starter.html)、[Alibaba 网关模块](https://github.com/alibaba/spring-cloud-alibaba/blob/2025.1.x/spring-cloud-alibaba-starters/spring-cloud-alibaba-sentinel-gateway/README.md)。

`shop-common` 为普通库，不引入这些 starters，不连接 Nacos，不启动进程。精确全局流控的 Token Server/client 依赖在 M6 按 Sentinel 1.8.9 集群方案单独配置。

同步业务调用使用 RESTful HTTP 接口，HTTP 客户端通过 LoadBalancer 解析 Nacos 中的服务名，不硬编码实例 IP。Nacos 的 gRPC 是基础设施通信；订单/库存/支付的异步命令仍通过 RabbitMQ 事件协议传递。

## 3. Nacos 环境与配置规划

| 维度 | 约定 |
|---|---|
| Namespace | dev/test/prod 各自独立；配置填实际 namespace ID，不能混淆显示名称。开发可创建 ID=flour-dev |
| 服务注册 Group | FLOUR_SHOP；网关和下游必须相同环境、相同 Group |
| 公共配置 Group | FLOUR_COMMON；DataId=shop-common.yaml |
| 应用配置 Group | FLOUR_APP；DataId=shop-user.yaml、shop-product.yaml、shop-inventory.yaml、shop-order.yaml、shop-payment.yaml、shop-gateway.yaml |
| Sentinel 规则 Group | SENTINEL_RULES；DataId 按应用和规则类型拆分 |
| 账号 | 应用仅有本环境注册/读取权限；配置发布账号独立；生产与开发账号分开 |

启动配置放本地 application.yml：应用名、Nacos 地址、namespace、凭证来源、显式 import。2025.1 分支使用 `spring.config.import`，不使用 bootstrap.yml 或默认自动加载 `${application.name}.yaml`，见 [Nacos Config 接入说明](https://sca.aliyun.com/en/docs/2025.x/user-guide/nacos/quick-start/)。

订单服务本地配置示例：

```yaml
server:
  port: 8084
spring:
  application:
    name: shop-order
  config:
    import:
      - "nacos:shop-common.yaml?group=FLOUR_COMMON&refreshEnabled=true"
      - "nacos:shop-order.yaml?group=FLOUR_APP&refreshEnabled=true"
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        namespace: ${NACOS_NAMESPACE:flour-dev}
        group: FLOUR_SHOP
        username: ${NACOS_USERNAME}
        password: ${NACOS_PASSWORD}
      config:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        namespace: ${NACOS_NAMESPACE:flour-dev}
        username: ${NACOS_USERNAME}
        password: ${NACOS_PASSWORD}
    sentinel:
      eager: true
      transport:
        dashboard: ${SENTINEL_DASHBOARD:127.0.0.1:8858}
        port: ${SENTINEL_TRANSPORT_PORT:8719}
```

这个示例仅设置 Nacos Config 与 Sentinel 监控连接；**Sentinel 规则还必须单独接入动态数据源**，不能以 import 了 yaml 就声称规则生效。生产必须显式提供环境、地址和秘密；数据库/MQ/Redis/JWT 私钥不放进公共配置，使用部署平台 Secret/环境变量引用。

关键配置缺失时启动失败，不对关键 import 使用 optional 来隐藏故障。非秘密配置通过经过校验的类型化配置对象生效；发布流程先校验，再灰度，再全量，失败回滚。金额/运费规则带版本并保存到订单快照，配置刷新不能追改历史订单或重建连接池/签名密钥。

## 4. Sentinel 资源与规则

HTTP URI 使用 RESTful 资源路径，Sentinel 资源使用稳定逻辑名，避免把每个 orderNo/skuId 变成一条资源。MVC URL 资源按路由模板聚合，业务方法显式定义关键资源；依赖调用记录真实超时/异常，普通业务校验失败不计为依赖故障。

| 层 | 资源或分组 | 第一版处理 |
|---|---|---|
| 网关 | routeId=order-create（POST /api/v1/orders） | 按 route 流控，单节点示例 200 QPS；阻断返回 429 |
| 网关 | 商品读取、认证、支付 API 分组 | 各自阈值，防止公开读取挤占交易入口；来源 IP 只信任受控代理 |
| order | order.create | 下单业务入口流控；多实例需要严格总配额时启用集群全局规则 |
| order | dependency.product.quote / dependency.user.address | 慢调用/异常熔断；权威报价或地址故障返回 503 |
| payment | payment.create / dependency.payment.channel | 支付入口与渠道并发/熔断；未知结果保留 UNKNOWN，进入查单 |
| inventory | inventory.reserve / inventory.confirm / inventory.release | 并发上限保护数据库；MQ 消费被阻断必须退避重试，不 ACK 丢弃 |

Gateway 独立匹配下单路由的配置节选，其他路由按接口资源逐一登记：

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: order-create
              uri: lb://shop-order
              predicates:
                - Path=/api/v1/orders
                - Method=POST
    sentinel:
      filter:
        enabled: false
```

网关走 Sentinel Gateway Adapter，规则类型使用 `gw-flow`、`gw-api-group`；业务服务用 `flow`、`degrade`、`param-flow`。网关关闭普通 Servlet URL filter，并通过网关 BlockRequestHandler 统一拒绝响应；不要把普通 flow 规则套到 GatewayFlowRule 上，见[Sentinel 集成规则说明](https://github.com/alibaba/spring-cloud-alibaba/wiki/sentinel)。

业务单实例示例：`shop-order-flow-rules.json` / Group `SENTINEL_RULES`：

```json
[
  {
    "resource": "order.create",
    "limitApp": "default",
    "grade": 1,
    "count": 200,
    "strategy": 0,
    "controlBehavior": 0,
    "clusterMode": false
  }
]
```

`grade=1` 为 QPS，`clusterMode=false` 为每实例配额。网关对应示例：`shop-gateway-gw-flow-rules.json`：

```json
[
  {
    "resource": "order-create",
    "resourceMode": 0,
    "grade": 1,
    "count": 200,
    "intervalSec": 1,
    "controlBehavior": 0
  }
]
```

业务熔断起点：对 dependency.product.quote 使用 500 ms 慢调用阈值、慢比例 50%、统计窗口 10 s、最小样本 20、熔断 10 s，随后半开探测。参数通过压测调整；必须把真实依赖失败记录到正确资源，不能只加注解而不验证统计。

## 5. 持久化、拒绝与全局流控

| 应用/规则 | DataId | 类型 |
|---|---|---|
| order 流控 | shop-order-flow-rules.json | flow |
| order 依赖熔断 | shop-order-degrade-rules.json | degrade |
| payment 流控/熔断 | shop-payment-flow-rules.json / shop-payment-degrade-rules.json | flow / degrade |
| inventory 并发保护 | shop-inventory-flow-rules.json | flow |
| 网关流控 | shop-gateway-gw-flow-rules.json | gw-flow |
| 网关自定义 API 分组 | shop-gateway-gw-api-group-rules.json | gw-api-group |

规则发布链路：仓库规则模板 → 校验/审计 → 发布 Nacos 的 SENTINEL_RULES → 应用订阅 → 类型校验 → 规则管理器更新。应用通过 `sentinel-datasource-nacos` 接入相同 namespace/group，凭证由外部注入；每个规则类型独立数据源。初始化与更新都要验证加载状态，配置错误保留最后有效规则并告警。

采用 Nacos 为唯一持久化发布源。原版 Dashboard 直接推送内存规则不能保证重启恢复；第一版通过受控 Nacos 发布流程修改，Dashboard 用于查看。若需要控制台编辑，必须实现写入 Nacos 的发布扩展。[动态规则官方说明](https://github.com/alibaba/Sentinel/wiki/Dynamic-Rule-Configuration)。

流控/热点/并发保护返回 HTTP 429、code=RATE_LIMITED、Retry-After；熔断返回 HTTP 503、code=CIRCUIT_OPEN；关键规则未就绪返回 503 RULES_NOT_READY。网关、MVC BlockExceptionHandler 与业务 blockHandler 使用相同业务包裹及 traceId。MQ 消费没有 HTTP 状态，阻断后进入有界退避与监控，业务未提交不能 ACK。

本地限流不会自动变全局限流。严格订单总配额用 Sentinel **业务 FlowRule 的集群模式**，在 order.create 配置唯一 flowId 与 thresholdType=1（global），客户端从 Token Server 获取配额；两副本合计 200 QPS，不能配置两份单机 200 并宣称总计 200。网关保持本地入口保护，不能直接假设 GatewayFlowRule 具备同样的集群语义。[集群流控官方说明](https://github.com/alibaba/Sentinel/wiki/Cluster-Flow-Control)。

Token Server 可用性、规则归属、客户端连接/超时都必须部署和验证，单设置 clusterMode=true 不算接入完成。默认失败回退本地可能扩大总配额，生产需显式实现并验证“拒绝新交易或按预定副本上限保守回退”策略，不能只调整一个布尔配置就声称故障时严格限额。用户维度跨实例防刷可使用 Redis 原子计数，但不代替 Sentinel 的业务/依赖保护。

## 6. 端口、部署与故障验收

Nacos 3.x API 与控制台分离，参见[官方部署端口说明](https://www.nacos.io/docs/latest/manual/admin/deployment/deployment-overview/)：

| 端口 | 开发映射与用途 |
|---|---|
| 8848 | Nacos API，开发绑定 127.0.0.1:8848 |
| 9848 | 客户端 gRPC，开发绑定 127.0.0.1:9848；主端口+1000 的映射关系要保持 |
| 8080（容器内） | Nacos 控制台映射 127.0.0.1:8849，避免占用商城网关 8080 |
| 9849、7848 | Nacos 集群内部通信；生产节点间开放，不对客户端/公网开放 |
| 8858 | Sentinel Dashboard，开发本机；需鉴权，生产仅运维网络 |
| 8719 起 | Sentinel 应用监控通信，配置可达地址；同机多实例分配不同端口，容器网络不能指向自身 localhost |

Compose 中应用使用 nacos:8848、sentinel-dashboard:8858，IDE 启动的应用使用本机映射端口；Dashboard 与应用的双向网络必须可达。Nacos 管理库与业务库分开，初始化 SQL 来自目标 Nacos Server 版本，不能复制老版本表结构。

开发用 standalone；生产至少三节点 Nacos 集群、独立高可用配置数据库及备份，注册/配置读写账号分权。Dashboard 不在用户请求链路中，其停机不影响已加载的本地规则。Nacos 故障时老实例保留最近有效配置/规则，新实例缺少关键配置与规则不得对外就绪；具体 readiness 检查需要项目实现，不是框架默认保证。

M0/M6 验收：注册两副本并可调用；配置环境隔离与灰度刷新；修改 Nacos 规则实时生效；重启恢复规则；达到阈值正确 429；依赖慢/失败触发 503 并半开恢复；Dashboard/Nacos/Token Server 分别停机时行为符合策略；支付未知状态及消息补偿继续可恢复。完整业务容量报告与真实部署验收之前，不宣称示例配置已达到生产容量。
