# RocketMQ 设计与验收

项目已有 [RocketMQ 5.x 基础接入](rocketmq-integration.md)。接入业务主题时，按客户端/Broker 版本与 ADR 落实以下流程。

1. 记录异步、解耦、削峰、延迟和最终一致性需求，比较同步调用、数据库任务与 MQ。锁定 Broker/NameServer、客户端和兼容矩阵。核对部署、HA、认证与最小 ACL。
2. 记录 Producer、Consumer、Topic、Tag、Group 与消费模式。版本化 envelope 带稳定 eventId、业务 Key、schemaVersion、occurredAt、traceId 和最小 payload，定义序列化及滚动发布。
3. 可靠投递使用同步结果或可观测异步回调。单向发送用于允许丢失的场景。区分确认、超时和永久失败，重投保留 eventId 并持久化状态。
4. 消费者校验协议及权限，去重记录和业务处理同事务提交，随后 ACK。区分临时和永久错误，定义重试上限、退避、死信告警及按原 ID 受控重放。ACK、重试与 DLQ 以目标 API 为准。
5. 需要局部顺序时定义路由键、顺序作用域、状态版本及失败阻塞策略。按生产消费速率、积压年龄、重试率、DLQ 和消费者健康设置监控及扩容，兼顾队列数与数据库压力。
6. 在隔离环境验证发送失败、重复、并发、提交后 ACK 丢失、宕机恢复、Broker 故障、顺序、积压及补偿。保留事务断言、事件 ID 和追踪证据。

见[数据库与消息一致性](database-consistency.md)。
