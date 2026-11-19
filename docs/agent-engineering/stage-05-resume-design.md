# 阶段 5 恢复方案

用户已确认升级依赖并接入 RocketMQ。保留 Java 17，以受维护的 Spring Boot 4.0、Spring Cloud 2025.1 和 Alibaba 2025.1 同代组合消除旧基线风险。适配 MyBatis、测试装配、网关配置和 JSON 兼容层，实际执行原有快速和隔离全量回归、依赖扫描与密钥门禁。[Alibaba 官方矩阵](https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/) · [Spring Cloud 发行列车](https://spring.io/projects/spring-cloud)

RocketMQ 采用独立的客户端库，支持稳定 ID、Tag、协议版本、Trace 和同步发送确认。订单、库存和支付状态机仍需业务规则；本次接入用隔离 Topic 及 MySQL 幂等副作用验收，保持现有请求路径和 Redis 缓存职责。

- 数据库：消费组与事件 ID 为持久唯一键，去重记录和副作用同一事务。业务失败回滚，提交成功才确认，重复 ACK 不执行副作用。持久化副作用只能通过同一 DataSource 的事务访问。
- Redis：该消费链无需缓存和短期防重，依赖 MySQL 保证重复投递下的一致性。
- RocketMQ：使用官方 5.x SDK，TLS/凭据由服务调用方显式配置。Producer/Consumer 生命周期受调用方管理。运行测试使用回环 Proxy 和独立 Topic/Group，消费失败触发 Broker 重投。正常消息不承诺顺序；需要顺序的业务按 FIFO 分组设计并测试。
- 发送可靠性：接收回执后才认为 Broker 已接收。发送超时保留原事件 ID，业务事务场景用同事务 Outbox 驱动发送，按目标服务的迁移、投递器和监控验收。客户端库不替调用者创建业务事务。
- 权限与日志：隔离拓扑测试使用无外网入口的本地 Broker；运行环境配置 Topic/Group 最小权限。日志记录错误类型、事件 ID、Topic、Group 和可用 Trace，省略凭据及消息正文。
- 验收：发送、重复、回滚后重投及 SDK 生命周期经真实 Broker + MySQL 执行。对积压、顺序、死信、Broker 中断及外部副作用以对应实际业务接口支持为前提逐项登记。所有门禁通过后恢复阶段 6。
