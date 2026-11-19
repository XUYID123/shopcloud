# 公共幂等注解与 Redis 防重复提交

日期：2026-10-23。适用当前 Java 17 / Boot 3.2.5。公共能力位于 shop-common，通过 Spring Boot 自动配置注册，无需扩大业务包扫描或手工导入切面。

## 1. 使用方式

在 Spring 管理的业务 Bean 的 public、非 final 同步方法上添加注解，从另一个 Bean 调用该代理方法：

```java
import org.spring.shopcommon.idempotent.Idempotent;

@Idempotent(
    prefix = "idempotent:order:submit:",
    expireTime = 10,
    message = "订单正在提交，请勿重复操作",
    key = "#userId + ':' + #dto.orderId"
)
public OrderVO submit(Long userId, OrderSubmitRequest dto) {
    // userId 来自已经验证的身份上下文；仍由业务层验证订单归属及状态。
    return orderService.submit(userId, dto);
}
```

以上为业务接入片段，不新增订单模块接口/DTO。最小写法为 `@Idempotent(key = "#dto.orderId")`，适用于订单 ID 本身已全局唯一且业务验证归属的场景。

| 注解属性 | 默认值/约束 | 含义 |
|---|---|---|
| prefix | `idempotent:`，1～128 位字母/数字/`:_-.` | Redis 命名空间；最终 Key 不是原始订单号 |
| expireTime | `5`，单位秒，1～31536000 | 首次占位开始的防重窗口 |
| message | 请求已提交，请勿重复操作 | 重复提交返回的公开提示 |
| key | **必填 SpEL**，最长 1024 字符 | 提取当前业务操作的稳定唯一维度 |

key 必填可以避免所有用户意外共用“空 Key”。表达式支持命名参数 `#dto.orderId`、索引别名 `#p0.orderId` / `#a0.orderId` 和 `#args[0].orderId`；多个维度可拼接。命名参数依赖 Java 编译的 `-parameters`，本项目 Boot 编译配置已保留；无法控制外部模块编译选项时使用索引别名。

key 的计算结果必须是非空标量（字符串、数字、布尔、UUID 或枚举），字符串长度最多 1024，不接受整个 DTO/集合/null。需要“用户＋操作号”时应明确编码两个维度，避免相同操作号在不同用户之间误拦截。不要使用每次请求都会变化的 traceId/时间戳作为防重 Key，也不要把密码放入 Key。

## 2. 文件与分工

| 文件 | 作用 |
|---|---|
| [Idempotent](../shop-common/src/main/java/org/spring/shopcommon/idempotent/Idempotent.java) | 方法级、运行时注解及四个属性 |
| [IdempotentKeyResolver](../shop-common/src/main/java/org/spring/shopcommon/idempotent/IdempotentKeyResolver.java) | SpEL、参数别名、配置校验、服务/方法维度和 SHA-256 摘要 |
| [IdempotentAspect](../shop-common/src/main/java/org/spring/shopcommon/idempotent/IdempotentAspect.java) | 方法拦截、占位、重复异常、执行原业务，不吞业务异常 |
| [RedisIdempotentStore](../shop-common/src/main/java/org/spring/shopcommon/idempotent/RedisIdempotentStore.java) | SET NX + TTL 原子命令、故障 503 |
| [IdempotentProperties](../shop-common/src/main/java/org/spring/shopcommon/idempotent/IdempotentProperties.java) | 可选独立 Redis 配置覆盖 |
| [IdempotentAutoConfiguration](../shop-common/src/main/java/org/spring/shopcommon/idempotent/IdempotentAutoConfiguration.java) | 按条件装配切面、解析器、存储与内部连接资源 |
| [AutoConfiguration.imports](../shop-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports) | 保留 Trace 注册，同时追加幂等配置类 |
| [ErrorCode](../shop-common/src/main/java/org/spring/shopcommon/exception/ErrorCode.java) | 新增 DUPLICATE_REQUEST，HTTP 409 |

common 增加受现有 Boot BOM 管理的 AOP 与同步 Redis Starter，父 POM 架构不变。消费服务已有 common 依赖即可使用，不必再写 @EnableAspectJAutoProxy；Boot 的 AopAutoConfiguration 创建代理。没有自动给现有接口加注解，开发者在需要防重的方法选择业务 Key。

## 3. 每次执行的步骤

1. Spring AOP 匹配带注解的方法，解析代理对应的实际实现方法/桥接方法及参数。
2. 拒绝声明为 Future、CompletionStage、Publisher 或带 @Async 的方法；当前模板只承诺同步方法。
3. 检查 TTL/prefix/message/key 配置；创建本次独立的 MethodBasedEvaluationContext。
4. 从源码注解解析/缓存 SpEL，使用本次参数求值；请求文本不能被当作表达式再次解析。
5. 对“服务名＋完整方法签名＋动态 Key”按长度编码后计算 SHA-256，得到 `prefix + 64位摘要`。
6. `StringRedisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(expireTime))` 一条命令同时占位和设置过期，不执行 GET 后 SET，不拆成 SETNX 后 EXPIRE。
7. true：执行 `joinPoint.proceed()`；false：抛 BusinessException(DUPLICATE_REQUEST, message)；null/Redis 故障：抛 DEPENDENCY_UNAVAILABLE，禁止无保护地执行业务。
8. 方法成功或异常后都保留标记，直到首次设置的 TTL 到期。不会 finally delete，也不会无条件删除可能被其他请求新占有的 Key。

服务/方法维度避免不同微服务、不同接口、方法重载误用同一个动态值时互相拦截。摘要避免直接把原始订单号或账号文本写入 Redis Key，但不代表低熵业务值经过摘要后就成为秘密凭据。

SpEL 来自开发者源码注解，是可信配置，能访问目标/参数对象。不要允许前端动态提供或修改表达式。异常响应和存储日志不输出参数、Key、密码或 SpEL 底层敏感细节。

## 4. Redis 实例与自动装配

防重标记不是可以随意淘汰的业务缓存，默认使用 **6380 noeviction 安全实例**，与现有限流/认证数据分命名空间；不写入 6381 allkeys-lfu 业务缓存。

连接参数优先级：`shop.idempotent.redis.host/port/password` 显式配置 → 已有 `SHOP_GATEWAY_REDIS_HOST/PORT/PASSWORD` 环境变量 → 本项目本地开发默认值。默认 host=127.0.0.1，port=6380，password=shop_gateway_local_dev，database=0，timeout=1s。本地默认凭据沿用现有 Compose；部署环境应通过秘密注入覆盖。

通常不必增加配置。需要自定义时写在业务服务原来的 application.yml/properties 中：

```yaml
shop:
  idempotent:
    enabled: true
    redis:
      host: ${SHOP_GATEWAY_REDIS_HOST:127.0.0.1}
      port: ${SHOP_GATEWAY_REDIS_PORT:6380}
      password: ${SHOP_GATEWAY_REDIS_PASSWORD:shop_gateway_local_dev}
      database: 0
      timeout: 1s
      # username: 可选 Redis ACL 用户
      ssl: false
```

Resources 内部持有 LettuceConnectionFactory 和 StringRedisTemplate，不把它们额外注册为按类型候选；因此 user 默认 6381 模板、6380 Refresh Token 资源和网关原有双 Redis 注入不变。连接和命令都有超时，关闭容器时销毁本组件拥有的客户端，不关闭 Redis 服务端。

自动配置仅在 Servlet 应用启用，WebFlux 网关不装配阻塞式幂等切面。可用 `shop.idempotent.enabled=false` 关闭；通过注册自己的 RedisIdempotentStore 可替换存储，默认内部连接资源会退让。连接资源是在客户端首次执行命令时建立网络连接，不要求没有使用幂等方法的骨架服务为启动而额外探测 Redis。

本次默认连接资源按项目单节点 Redis 创建，没有宣称已部署 Sentinel/Cluster。存储操作只有一个 Key，但故障切换、数据丢失的可靠性仍取决于实际 Redis 部署。

## 5. 错误与已有统一返回

| 情况 | 行为 |
|---|---|
| 同窗口重复提交 | BusinessException，409 DUPLICATE_REQUEST，使用注解 message |
| Redis 超时/连接故障/无法获得即时结果 | 503 DEPENDENCY_UNAVAILABLE，不进入业务 |
| 原业务异常 | 原样透传，不归类为 Redis 故障 |
| 非法 SpEL、空 Key、错误 TTL、异步方法 | 配置错误 IllegalStateException，已有未知异常处理返回 500 |

shop-user 已注册 common 的 GlobalExceptionHandler，会自动按现有 Result 格式返回。其他服务应继续使用自己的业务异常处理约定，若尚未注册统一处理器，可沿用 user 的 `@Import(GlobalExceptionHandler.class)`；公共幂等切面本身无需手工导入。

重复响应示例：

```json
{
  "code": "DUPLICATE_REQUEST",
  "message": "订单正在提交，请勿重复操作",
  "data": null,
  "traceId": "与请求/响应头一致"
}
```

网络超时可能发生在 Redis 已成功写入但客户端未获确认时；本次选择拒绝执行业务并保留可能存在的短窗口标记，不盲目重试写入或删除不确定状态。

## 6. 语义边界

这是**时间窗口防重复提交**，没有缓存业务响应或保证永久 exactly-once：

- 方法结束后立即删除 Key 会放开顺序重复，本实现保留至 TTL。
- 失败后也保留窗口，避免外部副作用已发生却因盲目重试而重复；本次不增加“失败立即释放”开关。
- TTL 从占位时算起，不自动续期。执行/排队/事务提交若超过 TTL，另一请求可能再次进入，所以应让 expireTime 覆盖业务最大耗时，并用数据库唯一约束/状态条件更新保证最终正确性。
- 代理外调用、this 自调用、private/static/final 方法不能依赖此注解拦截。推荐将注解写在实现类的 public 方法，而非只写接口声明。
- 切面默认位于事务拦截器外侧；调用方如果已有更外层事务，注解本身无法延长到那个外层事务提交。不能将固定 TTL 当作完整的事务幂等协议。
- 同一个操作若每次产生不同 Key，本组件无法判断它们是重复。客户端重复请求应复用同一业务操作号，并在业务层校验归属与请求内容一致性。
- 订单、支付、消息消费仍需长期幂等记录、业务唯一键和状态机；Redis 丢失/重启/切换可能削弱短窗口保护，不能替代这些约束。

## 7. 验证

测试覆盖命名参数/所有索引别名、非法 Key、服务/方法隔离、CGLIB/JDK 代理、仅成功占位者执行、业务异常原样透传、Redis 故障与 null、自动配置与存储替换、Boot SPI 与 Trace 共存、统一 HTTP 409/503/Trace ID，以及真实 Redis 下两组代理的 32 路并发单次执行、TTL 到期重入、失败保留、6380/6381 数据隔离。测试只删除自己的随机作用域 Key，不执行 FLUSHDB/扫描删除其他数据。

2026-10-23 实际执行：

```text
mvn -o -ntp package -Dspring.cloud.nacos.discovery.enabled=false
```

**BUILD SUCCESS，共 201 项测试，失败/错误/跳过均为 0**：common 27、cache 22、user 62、gateway 86、product/cart/order/pay 各 1。全部模块已打包，完整记录见 [全模块构建日志](../target/idempotent-full-build.log)。common 中新增本次 15 项测试，原 Trace 的 12 项继续通过；网关启动测试还验证了阻塞幂等切面没有在 WebFlux 中启用。

首次独立 common 测试的 HTTP 测试夹具曾因泛型 getProxy() 与 Java 可变参数推断产生 ClassCastException，改为显式控制器类型后，完整构建中的 HTTP 200/409/503/Trace 校验已通过。该问题没有更改生产方法执行语义。

本次真实 Redis 并发测试使用本机已有两个容器，MySQL/登录/RBAC/网关保护也参与回归；Nacos 服务发现显式关闭。未运行生产压测、Redis 主备切换/数据丢失验证或长期订单幂等业务验收，不宣称这些场景已通过。

实现参考：[Spring Data Redis 原子 setIfAbsent](https://docs.spring.io/spring-data/data-redis/docs/current/api/org/springframework/data/redis/core/ValueOperations.html)、[MethodBasedEvaluationContext 参数绑定](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/expression/MethodBasedEvaluationContext.html)、[Boot 自动配置 SPI](https://docs.spring.io/spring-boot/3.4/reference/features/developing-auto-configuration.html)。代码按本项目固定版本编译验证。
