# 阶段 7 实战验收

按[六步流程](../standards/development-lifecycle.md)做需求、方案和模拟代码审查。依据为当前源码、历史契约及[阶段 5 实际测试](stage-05-quality.md)。评分衡量工程分析与验证方案。尚无业务实现的场景，其测试在本阶段是验收设计。

## A · 新增商品

使用 feature-development、database-design、security-review、code-review。[商品入口](../../shop-product/src/main/java/org/spring/shopproduct/controller/ProductController.java)和[写服务](../../shop-product/src/main/java/org/spring/shopproduct/service/ProductService.java)已有完整创建路径。输入是后台新增 SPU，输出 201、Location 和 OFF_SHELF 商品，版本为 1。SKU、库存及上架分别使用自己的业务边界。

| 检查 | 分数 | 分析、处理与验证 |
|---|---:|---|
| 规范 | 3 | 从 AGENTS 到组件目录、商品业务和 DoD，先查现有 POST；按字段和状态契约复查范围 |
| 组件复用 | 3 | 沿用 ProductCreateRequest、Result、ProductCreatedVO 和 @Valid；HTTP 测试断言响应与入库 ID |
| 数据库 | 3 | 同事务对分类 FOR SHARE，外键约束，SPU 初始版本由服务定；测试分类不存在或禁用时零写 |
| Redis | 3 | 新建下架 SPU，既有公开商品查询语义不变；基本验证为公开端仍不可读，后续上架走已有失效 |
| RocketMQ | 3 | 创建返回数据库提交结果，无新异步消费需求；审查变更和依赖中只有 MySQL 写 |
| 异常 | 3 | 分类缺失、禁用、输入错误和持久化不可用分别映射既有业务码；断言状态码与零写 |
| 日志 | 3 | PRODUCT_CREATE 使用 @BizLog，关联 traceId 与 productId；用现有 BizLog 测试验证事务结果 |
| 权限 | 3 | 网关 product:write 和下游路径/受众绑定；测试伪造、过期、错误受众和匿名请求 |
| 参数 | 3 | DTO 和 JSON 严格处理 ID 上界、未知字段、枚举与状态/版本；[现有创建测试](../../shop-product/src/test/java/org/spring/shopproduct/ProductIntegrationTests.java)覆盖 |

A 得 **27/27**。代码审查核对请求字段白名单、同分类并发情况和已有响应契约。

## B · 修改商品

使用 feature-development、database-design、redis-design、concurrency-design、security-review、code-review。将需求限定为后续后台基础信息修改，销售状态仍通过已有子资源。沿用[商品并发 ADR](../decisions/0003-product-concurrency.md)，以[现有失效测试](../../shop-product/src/test/java/org/spring/shopproduct/ProductCacheInvalidationIntegrationTests.java)为反例基线。

| 检查 | 分数 | 分析、处理与验证 |
|---|---:|---|
| 缓存失效 | 3 | 同事务登记现有详情 Key 的失效任务，按修改类型约定延迟补偿；预热详情后修改并读回新值 |
| 并发更新 | 3 | 后台授权后按 productId 与期望 version 更新；相同版本并发仅一方成功，另一方返回冲突 |
| DB 与 Redis | 2 | MySQL 为事实来源，回滚时失效行随业务回滚；并发回填用复删缩短窗口。用屏障重现回填，陈旧 SLA 上限待业务确认 |
| 消息通知 | 3 | 单服务更新和已存 DB 任务足够。出现独立订阅方且有可靠事件要求时，再审查 Outbox 与消息协议 |
| 缓存失败 | 3 | Redis 失败走已有回源与任务重试，记录积压和最老失败时间。隔离测试断 Redis、回源并恢复重试 |

B 得 **14/15**。审查同时核对禁写状态/版本等服务端管理字段、后台授权和冲突返回。

## C · 库存扣减

使用 feature-development、database-design、concurrency-design、security-review、code-review。业务当前无库存接口。演练输入为经授权的订单项、SKU、正数数量及稳定操作 ID。出库、预占与释放的业务时点在真正实施前确定。

| 检查 | 分数 | 分析、处理与验证 |
|---|---:|---|
| 防超卖 | 3 | 同库执行 available >= qty 条件扣减，检查影响行数；并发总扣减不得超过初始可用量 |
| 幂等 | 3 | 操作 ID、请求指纹、结果及库存写同事务。相同请求读回已存结果，使用相同 ID 的不同请求返回冲突 |
| 锁或条件更新 | 3 | 以 SKU 主键和库存下界条件更新，批量固定锁顺序；测试库存下界、冲突和死锁重试预算 |
| Redis | 3 | 首版选择 MySQL 为事实来源与唯一写入权威；关闭 Redis 验证扣减正确性 |
| 削峰 | 2 | 先测热点 SKU 和锁队列，超过业务延迟预算才评估 RocketMQ 排队。实际吞吐与同步预算待业务数据 |
| 重复消息 | 3 | 异步触发仍以持久操作 ID 保证幂等；并发重投与跨事件重复申请只成功一次 |
| DB 一致性 | 3 | 库存、幂等结果与记录同事务，失败全回滚；订单跨库状态用事件恢复与有界补偿 |

C 得 **20/21**。模拟审查把「应用先读库存再扣减」作为否决缺口，修订方案包含 DB 下界与行数验证。验收包括初始 10 件、并发 20 个不同请求，恰好 10 个成功且库存为 0，另测重复请求、回滚和部门权限。

## D · 订单异步处理

使用 feature-development、rocketmq-design、database-design、concurrency-design、security-review、code-review。假设订单合法迁移后，需要与主请求解耦的可重试任务。已有 [shop-order Producer](../../shop-order/src/main/java/org/spring/shoporder/messaging/OrderRocketMqConfiguration.java)和[传输/消费幂等组件](../messaging/rocketmq-integration.md)。下列 Topic、Tag 和事件为方案候选。

| 检查 | 分数 | 分析、处理与验证 |
|---|---:|---|
| Producer | 3 | 订单事务登记 Outbox，独立发送器同步取结果并记录确认；模拟超时后重发同 ID |
| Consumer | 3 | 独立组、最小 ACL、校验 envelope，事务完成后 ACK；测试格式错误、越权或未知版本拒绝 |
| Topic | 3 | 候选 shop-order-events，域与环境隔离，启动验收核对 normal 类型和授权 |
| Tag | 3 | 候选 ORDER_TASK_REQUESTED，订阅测试证明目标事件到达且其他 Tag 被过滤 |
| 事件 ID | 3 | Outbox 创建 UUID，发件、重试、重放保持原 ID；记录 Broker 消息 ID 供观测 |
| 版本 | 3 | schemaVersion 初始为 1，允许向后兼容字段扩展；用旧样本和未知版本验收 |
| 幂等 | 3 | 消费组+事件 ID 为 Inbox PK，并与本地状态同事务，重复确认；业务键兜底跨事件重发。真实用例全量已通过 |
| 重试 | 3 | 可恢复失败回滚并由 Broker 退避重投，处理完成再 ACK。真实重试用例断言回滚与再提交 |
| 死信 | 2 | 限次、告警、诊断和按原 ID 重放。生产审批角色、SLA 和消费组配置待业务确认 |
| 积压 | 2 | 告警依据最老未完成时间与待消费量，控制消费并发和数据库负载。实际峰值和阈值待测 |
| 消费者宕机 | 3 | 分别模拟提交前中断与提交后 ACK 丢失，重启时断言恢复与副作用只发生一次 |
| Broker 故障 | 2 | 发送失败留在 Outbox，恢复后续投；隔离测试可断开后恢复，实际 HA 拓扑待部署选择 |
| DB 与 MQ | 3 | 业务和 Outbox 同事务，受控认领、超时可恢复；发送 ACK 丢失按重复处理，验证只提交一侧的异常路径 |
| 日志与监控 | 3 | 关联 traceId、eventId、Topic、Group、重试次数与结果；断言异常日志无正文并接入故障告警演练 |
| 补偿 | 3 | 本地写失败先回滚，外部副作用以持久任务+下游幂等键恢复，状态机防回退；注入接收后失败并重放 |

D 得 **42/45**。如果订单状态严格有序，先确认按 orderId 隔离顺序需求，再选择 FIFO 主题；当前普通 Topic 的验证结论限于投递、幂等和重试。

## 自检与结论

得分 **103/108（95.4/100）**。四个场景均有可追溯入口、职责、取舍和验证方式。未发现方案中有虚构组件、无依据加中间件、漏掉重复/一致性、明显越权、可超卖或缺少验收的否决情况。缓存时限、真实吞吐和生产 MQ 运维是已记录的范围缺口。阶段 7 为分析和模拟审查，已核对本阶段源码摘要。

## Harness 2.0 复核

- [stage-07]: ACCEPTED · 2026-11-20 · 旧场景与本轮商品、异步订单、路由验收记录相符。模拟方案与真实执行分开记录。
