# 升级验收业务演练

2026-11-20 · 两个独立模拟业务任务。真实源码、测试记录与模型判断分别注明。工作区内 Java、POM、迁移及应用资源保持既有摘要。

# ROUTER-DEMO-PRODUCT · 开发商品新增功能

## Requirement Analysis

主路由 ROUTE-05，STANDARD / MEDIUM。已读根 AGENTS、业务索引、商品并发 ADR 和 feature-development。定位 [ProductController](../../../shop-product/src/main/java/org/spring/shopproduct/controller/ProductController.java)、[ProductService](../../../shop-product/src/main/java/org/spring/shopproduct/service/ProductService.java)和[现有接口](../../26-新增商品SPU接口.md)。当前后台 POST 已返回 201、Location 和初始下架商品，先确认是否有增量需求。受影响模块为 shop-product、shop-gateway，当前阶段 Delivery。

## Technical Design

| 评估 | 决定与依据 |
|---|---|
| MySQL | REQUIRED，事务持有分类共享锁，外键保证关联，写 SPU 设置初始状态和版本 |
| Redis | NOT_REQUIRED，下架创建不改变公开商品详情，无新增缓存或原子操作 |
| RocketMQ | NOT_REQUIRED，创建同步提交，暂无独立消费者或削峰要求 |
| 异常 | REQUIRED，复用 BusinessException、ErrorCode、Result、全局异常出口 |
| 日志 | REQUIRED，复用 PRODUCT_CREATE 的 BizLog 和 TraceContext，日志标注事务结果和商品 ID |
| 校验 | REQUIRED，复用 DTO 白名单、Valid 和分类业务校验 |
| 权限及安全 | REQUIRED，后台 product:write 与内部 JWT，检查外部身份和管理字段 |
| 性能 | NOT_REQUIRED，沿用主键分类查询和短事务。当前需求未提出吞吐 SLA |
| 测试 | REQUIRED，覆盖成功、禁用分类、非法输入、匿名和伪造身份 |

## Design Review

database-design 和 security-review 核查锁范围、唯一性要求及后台鉴权；code-review 确认已有实现覆盖请求，重复增加同一路由会制造冲突。当前无需用户确认，后续新增规则另归具体需求。

## Implementation

演练确认当前请求由已有能力满足。修改文件无，按既有实现交付。

## Verification

已定位 [ProductIntegrationTests](../../../shop-product/src/test/java/org/spring/shopproduct/ProductIntegrationTests.java)，验证 201、Location、入库状态、错误码和未授权零写。历史隔离全量由[运行基线](../evidence/runtime-baseline.json)记录，本次演练未重新执行应用运行测试，状态 NOT_RUN。

## Delivery

已确认入口、组件、九项决定、事务和风险。无待办或外部阻塞。商户归属、批量新增等增量规则由提出时再明确。

# ROUTER-DEMO-ORDER · 异步订单处理功能

## Requirement Analysis

主路由 ROUTE-05，COMPLEX / HIGH。读主技能、rocketmq-design、database-design、concurrency-design、security-review、code-review，以及[消息 ADR](../../decisions/0004-rocketmq-transport.md)。shop-order 已有可选 Producer，订单状态机尚处业务规划。目标假设需要在合法订单状态迁移后执行独立下游任务。当前阶段 Delivery（设计演练），受影响模块为 shop-order、shop-messaging、数据库与运维配置。

## Technical Design

| 评估 | 决定与依据 |
|---|---|
| MySQL | REQUIRED，候选 transactional Outbox，订单状态与事件同事务，持久租约与重试时间 |
| Redis | NOT_REQUIRED，短窗口去重无法保护持久业务写，使用同事务 Inbox |
| RocketMQ | REQUIRED，演练假设确有跨服务独立重试与解耦。候选 Topic shop-order-events、Tag ORDER_TASK_REQUESTED、按环境消费组，稳定 eventId、schemaVersion、traceId；同步回执，超时同 ID 重发 |
| 异常 | REQUIRED，回滚后返回消费失败，可重试错误按组退避，限次进 DLQ 并按原 ID 受控重放 |
| 日志 | REQUIRED，记录事件与 Trace、发送状态、失败级别、积压年龄和 DLQ 告警，无正文或凭据 |
| 校验 | REQUIRED，校验 envelope、版本、业务键和状态迁移 |
| 权限及安全 | REQUIRED，环境隔离、TLS、最小 Topic/Group ACL，业务资源授权由服务验证 |
| 性能 | NEEDS_CONFIRMATION，实际积压 SLA、并发和顺序范围需业务流量数据 |
| 测试 | REQUIRED，真实 Broker 验证重复、回滚后重试、发送不确定，按用例加入 DLQ、积压、乱序、重启、Broker 停机与恢复 |

## Design Review

比较同步调用、DB 任务、RocketMQ。业务与 Outbox 同事务保证提交，消费以 group+eventId 唯一，Inbox 与状态写在同一事务，提交后 ACK。断点包括事务回滚、发送确认丢失、消费提交后 ACK 丢失。外部副作用用独立持久任务及下游幂等键。无真实严格顺序需求前不假定全局顺序。业务触发状态和重放操作权限待明确，原因已记入设计。

## Implementation

按验收范围模拟评审，候选协议及业务写入保留设计状态。修改文件无。现有基础组件见 [RocketMQ 接入](../../messaging/rocketmq-integration.md)和[一致性说明](../../messaging/database-consistency.md)。

## Verification

[真实消息回归](../../../shop-messaging/src/test/java/org/spring/shopmessaging/RocketMqIntegrationTests.java)在保留的全量基线中覆盖两个 Broker ID 同 eventId 一次提交、首次失败回滚后重投。业务状态机、FIFO、DLQ 重放和 HA 按未来具体用例验证，本轮状态 NOT_RUN。

## Delivery

记录候选协议、事务和依赖取舍，并保留未决时点、顺序、容量与生产政策。当前只需复验设计和规范覆盖。实际实施从明确业务规则继续。
