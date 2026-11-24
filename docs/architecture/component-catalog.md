# 公共组件目录

| 组件及代码 | 用法和边界 | 重复实现 |
|---|---|---|
| [Result](../../shop-common/src/main/java/org/spring/shopcommon/result/Result.java)、[GlobalExceptionHandler](../../shop-common/src/main/java/org/spring/shopcommon/result/GlobalExceptionHandler.java) | MVC 返回 Result，user、product、inventory 启动类显式导入 Advice 处理 HTTP 状态和业务码；过滤器出口检查对应映射 | 禁止 |
| [BusinessException、ErrorCode](../../shop-common/src/main/java/org/spring/shopcommon/exception) | Service 抛类型化业务异常，保留原因和对外信息边界 | 禁止 |
| [GatewayErrorWebExceptionHandler、GatewayException](../../shop-gateway/src/main/java/org/spring/shopgateway/error) | WebFlux 异常出口，保留下游可用响应 | 禁止 |
| [BizLog、BizLogAspect、ServletAccessLog、LogValues](../../shop-common/src/main/java/org/spring/shopcommon/logging) | 业务写操作标注动作和安全 ID；核对事务完成语义 | 禁止 |
| [TraceContext、TraceFeignRequestInterceptor、MdcTaskDecorator](../../shop-common/src/main/java/org/spring/shopcommon/trace) | HTTP、Feign、异步透传 Trace；WebFlux 用网关 Reactor 配置 | 禁止 |
| [内部 JWT、InternalIdentity](../../shop-common/src/main/java/org/spring/shopcommon/internalauth) | 从验签结果取可信主体，业务层核对归属 | 禁止 |
| [JWT/RBAC、GatewayAccessService](../../shop-gateway/src/main/java/org/spring/shopgateway/auth) | 按路由授权并签发受众与请求绑定的内部 JWT | 禁止 |
| [DTO/VO、Jakarta Validation](../../shop-product/src/main/java/org/spring/shopproduct/controller) | 专用请求校验和响应 VO；跨字段约束交给 Service | 按场景新增 DTO/VO |
| [ProductListQuery](../../shop-product/src/main/java/org/spring/shopproduct/dto/ProductListQuery.java)、[ProductPageVO](../../shop-product/src/main/java/org/spring/shopproduct/vo/ProductPageVO.java) | 商品分页，契约一致时复用 | 跨模块按需求 |
| [SafeCacheTemplate](../../shop-cache/src/main/java/org/spring/shopcache/SafeCacheTemplate.java) | 同步 Redis 缓存，配置 TTL、故障策略和锁；WebFlux 禁止直接阻塞调用 | 优先复用 |
| [商品缓存](../../shop-product/src/main/java/org/spring/shopproduct/cache) | 公开详情接入既有 Key、序列化及失效任务 | 禁止重造 |
| [@Idempotent、RedisIdempotentStore](../../shop-common/src/main/java/org/spring/shopcommon/idempotent) | SpEL + 原子 SET NX/TTL，短窗口防重；持久业务幂等由数据库保证 | 禁止重造 |
| [用户事务模板](../../shop-user/src/main/java/org/spring/shopuser/service/UserService.java)、[商品 Service](../../shop-product/src/main/java/org/spring/shopproduct/service) | Spring TransactionTemplate / @Transactional；留意代理与回滚 | 用 Spring 标准事务 |
| [RocketMQ 基础接入](../messaging/rocketmq-integration.md) | 显式 Topic/Tag 同步发送，消费提交后 ACK。复用同事务 Inbox 去重与 Outbox 租约重试，服务拥有其消息合同、迁移和业务规则 | 优先复用 |

暂无 ApiResponse 或跨模块通用分页。
