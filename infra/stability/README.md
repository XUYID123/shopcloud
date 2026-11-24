# 稳定性演练与恢复

## Nacos 和 Sentinel

`nacos-cluster.compose.yaml` 提供三节点 Nacos 3.2.4 与独立 MySQL 配置库。三组客户端端口只绑定回环。设置四个 `STABILITY_NACOS_*` 凭据变量并使用独立 Compose 项目启动。首次运行按 [Nacos 管理员初始化](https://nacos.io/en/docs/v3.2/manual/admin/auth/)创建管理员。应用通过 `NACOS_SERVER_ADDR`、`NACOS_NAMESPACE`、`NACOS_DISCOVERY_GROUP`、`NACOS_USERNAME` 和 `NACOS_PASSWORD` 使用环境专用配置。实际部署为应用与规则发布分别配置凭据和权限。

在目标 namespace 的 `SENTINEL_RULES` 组发布 JSON 文档。规则格式统一为 `{"qps":200}`，阈值为 1–100000 的整数。可使用 [Nacos Admin API](https://nacos.io/en/docs/v3.2/manual/admin/admin-api/)或控制台发布。

| Data ID | 启用方式 | 配额 |
| --- | --- | --- |
| `shop-gateway-capacity.json` | 网关上设置 `SHOP_GATEWAY_SENTINEL_NACOS_RULES_ENABLED=true` | 每个实例独立的 `gateway.capacity`，超限 429 |
| `shop-order-create.json` | 启动独立 Token Server，并在订单副本上设置 `SHOP_ORDER_CLUSTER_LIMIT_ENABLED=true` | 整组订单实例共享 `order.create`，超限 429，令牌服务不可用时新下单 503 |

两个规则使用者在启动时读取并校验规则，运行时按 2 秒间隔检查变更。无效规则和连接故障保留上一条有效规则。恢复时从 Nacos 加载。Token Server 与业务服务分进程运行，指向本环境的 Nacos。解开订单服务的 Boot JAR 后，在解包路径执行如下命令。Windows 类路径分隔符为 `;`，Linux 为 `:`。

```powershell
java -cp "BOOT-INF/classes;BOOT-INF/lib/*" org.spring.shoporder.admission.OrderTokenServer
```

默认 Token Server TCP 端口 18730。服务与客户端共用 `SHOP_ORDER_TOKEN_SERVER_PORT`，订单副本另设 `SHOP_ORDER_TOKEN_SERVER_HOST`。Token 端口放在只允许订单实例访问的私网。每个配额只运行一个活动 Token Server，客户端配置同一个地址。恢复新实例时复用该地址和 Nacos 规则。控制台用于观察，规则由 Nacos 发布。

`SHOP_ORDER_CHECKOUT_CIRCUIT_ENABLED=true` 启用订单服务的商品报价与用户地址依赖熔断。每个依赖分别统计异常率与慢调用，开启时返回 503 `CIRCUIT_OPEN`。每 10 秒窗口至少 20 个样本，50% 异常或超过 500 ms 的慢调用达到 50% 时熔断，10 秒后探测恢复。

## 交易检查与告警

订单、库存和支付分别为持久状态负责。调度器可在各服务配置 `SHOP_TRADE_OUTBOX_WORKERS`（默认 1，最多 8）和 `SHOP_TRADE_OUTBOX_POLL_MS`（默认 500）。工作线程用数据库行租约竞争任务，消费方通过 Inbox 保持幂等。发送失败按 2、4、8 秒退避，后续每次间隔为 16–19 秒。横向扩容时按连接池、数据库等待和 MQ 延迟调整工作线程数量。

给各库配置只读巡检账号，每个库用独立连接参数运行。

```powershell
python infra/stability/trade_health.py order --port 3310 --user trade_monitor
```

换用 `inventory` 或 `pay` 抓取对应库。通过环境注入 `MYSQL_PWD`。退出码 0 表示本次无异常，1 表示检测到异常，2 表示查询失败。 `--prometheus` 可供采集代理写入 textfile 文件，配合 `trade-alerts.yaml` 监控积压、业务滞留、余额不一致与巡检过期。部署时周期性原子替换文件，失败时记录执行结果。

## Broker 重试与安全恢复

1. 使用订单号、Trace、业务状态、发件箱事件 ID 和 Broker message ID 对账。过期未支付由订单超时扫描处理。PREPARING、CLOSING、库存确认/释放待处理、退款等待及 Mock 回调等待优先查看源服务 Outbox 或回调任务。命令已经入库而订单缺结果时查看目标服务的结果 Outbox。
2. 收到失败日志时检查 `ROCKETMQ_CONSUME_FAILED` 里的消费组与投递次数，通过隔离运维端查对应消费组的 Broker `%DLQ%`。消息重试上限由消费组配置。先修复失败原因和查证已提交的 Inbox。发送失败由 Outbox 退避重试，保留原事件 ID。[RocketMQ 消费重试](https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/)
3. 用 `redrive_outbox.py <service> --port <port> --user <user> --event-id <uuid>` 预览对应源服务事件。审核后由获授权的运维人员加上 `--execute --expected-broker-id <原回执>`，发布同一事件。脚本用预览回执作为写入条件，保留原标签和载荷。再查 Broker 回执、Inbox 与业务最终状态。人工处理支付异常按现有支付查证流程执行。

## 备份恢复

`restore_order_backup.py` 使用 MySQL 工具做演练。连接隔离实例，预建空的 `flour_order_restore_<suffix>`，暂停该演练库的新增交易，按脚本参数导出并恢复。脚本只写指定空库和新文件，比较订单、物流及消息表计数和关键业务字段摘要。数据库恢复报告需单独记录来源、时间、数据范围、耗时和凭据管理方式。
