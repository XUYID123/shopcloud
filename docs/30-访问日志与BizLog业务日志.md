# 访问日志、业务操作日志和 Trace ID

实现日期：2026-11-02。保留已有 Trace ID 传播，通过公共注解和入口日志补齐业务可观测性。

## 已有追踪与本次新增

已有网关 Reactor Context、Servlet MDC、Feign RequestInterceptor、MdcTaskDecorator、响应头和 Result.traceId 保持原约定。X-Trace-Id 是日志关联号，X-Request-Id 为同值兼容头，不用于认证或权限判断。本次没有增加 Span、采样上报器或链路瀑布图。

新增组件位于 shop-common 的 logging 包：

| 类 | 作用 |
|---|---|
| BizLog | 方法注解：action、bizId（只读 SpEL）、level |
| BizLogAspect | 同步 Service 操作计时、事务结果确认、安全字段输出 |
| BizLogAutoConfiguration | 通过 Boot imports SPI 自动注册，仅 Servlet 应用启用 |
| ServletAccessLog | 在既有 TraceServletFilter 中观察同步/异步请求结束，输出一次访问日志 |
| LogValues | 只接收标量、长度限制 256 字符、控制字符和空白替换，不自动打印对象 |

网关新增 GatewayAccessLog，由 GatewayRequestFilter 的 Reactor 回调触发，不使用同步业务切面。

## 使用 @BizLog

```java
@BizLog(action = "PRODUCT_SALE_STATUS_CHANGE", bizId = "#p0")
@Transactional
public ProductCreatedVO changeStatus(String productId, ProductSaleStatusRequest request) {
    // 原有上架/下架逻辑。
}
```

```java
@BizLog(action = "PRODUCT_CREATE", bizId = "#result.productId")
@Transactional
public ProductCreatedVO create(ProductCreateRequest request) {
    // 返回值仅提取 productId，不序列化整个对象。
}
```

- action 使用稳定操作名，便于跨版本搜索，不拼接用户名或输入正文。
- bizId 支持 #p0、#p1 等位置参数及 #result。只读数据绑定禁止类型引用、构造器和任意方法调用。只选择 ID 等非敏感字段，不指定密码、Token 等属性。
- 表达式取值失败时记录 bizId=-，不改变原业务结果。未提交成功的创建操作可能没有生成 ID，失败日志允许 bizId=-。
- level 默认 INFO；高频查询标记 DEBUG。DEBUG 关闭时直接执行业务，不计算业务日志字段。
- 必须经 Spring 代理调用，类内自调用不会触发；只支持同步 Service 方法。Future、CompletionStage 和可识别的响应式返回类型被拒绝，避免误把异步对象创建时间记录为执行时间。

当前已接入 USER_REGISTER、AUTH_LOGIN、AUTH_REFRESH、AUTH_LOGOUT、CATEGORY_CREATE、PRODUCT_CREATE、SKU_CREATE、PRODUCT_SALE_STATUS_CHANGE。PRODUCT_DETAIL 默认 DEBUG。登出不从 Refresh Token 提取日志字段，身份未知时 userId/bizId=-。

## 事务与身份

业务切面以高优先级包住默认事务拦截器，正常返回时本方法自有事务已经提交；提交抛异常记录 FAILED，不记录 SUCCESS。

若加入外层事务，方法返回时仅登记 TransactionSynchronization，afterCompletion 根据实际状态输出 SUCCESS、ROLLED_BACK 或 UNKNOWN。回调捕获标量 Trace ID 和可信用户 ID，不依赖完成时工作线程的旧 MDC。耗时含等待外层事务完成的时间。

业务拒绝输出 REJECTED 和业务码，未知异常输出 FAILED；切面原样抛出异常，不改变事务、返回值或异常分类，不打印重复堆栈。业务结果日志不是可靠审计表，进程退出、磁盘故障或队列溢出可能丢失记录，不作为资金等事实的唯一凭据。

userId 只读取 InternalAuthenticationFilter 验签后设置的 InternalIdentity.ATTRIBUTE，绝不从外部 X-User-Id 取值。匿名登录/注册时 actor 仍是匿名，成功返回的用户 ID 作为 bizId，并不伪装成请求已经携带登录身份。

## 访问日志

普通请求示例（服务名、线程与 Trace ID 由公共 Pattern 添加）：

```text
INFO [shop-gateway] [reactor-http-nio-3] [abc123] ... event=HTTP_ACCESS method=PUT path=/api/v1/admin/products/10001/sale-status status=200 routeId=shop-product-api durationMs=25 outcome=COMPLETE phase=complete
INFO [shop-product] [http-nio-8082-exec-1] [abc123] ... event=BIZ_OPERATION action=PRODUCT_SALE_STATUS_CHANGE bizId=10001 userId=42 outcome=SUCCESS code=- durationMs=12
INFO [shop-product] [http-nio-8082-exec-1] [abc123] ... event=HTTP_ACCESS method=PUT path=/api/v1/admin/products/10001/sale-status status=200 userId=42 durationMs=18 outcome=COMPLETE
```

HTTP 的 COMPLETE 表示处理链正常完成，不能理解为业务成功；400/401/403/404/409/429/503 都可能正常完成 HTTP 错误响应。业务结果以 BIZ_OPERATION.outcome 为准。

- Servlet：同步请求在过滤链返回时输出；异步请求注册 AsyncListener，等待 onComplete 后输出。共享一次性标记避免分派/回调重复输出。未处理异常若尚无错误状态，按 500 记录；这类日志是过滤链观察结果，不保证包含容器最终网络发送耗时。
- 网关：正常响应和取消在 doFinally 中输出。外层 ErrorWebExceptionHandler/Sentinel 的错误响应不在内部过滤链中，使用原响应 beforeCommit 回调获得最终错误状态，标记 phase=headers；此时 durationMs 截止到响应头提交，不能解释为响应体传输完毕。
- 网关取消标记 CANCELLED；若尚无状态，记录 0，避免伪造成功或已发送的 HTTP 状态。已提交响应后传输异常保留已提交状态并标记 ERROR。
- 访问日志只记录方法、无查询串的路径、状态、耗时及路由/可信用户 ID，不读取或缓存请求体，不打印 Authorization、内部 JWT、Refresh Token 或完整查询参数。

## 异常与输出

BusinessException 新增 (ErrorCode, Throwable) 构造器，允许保留依赖故障原因，对外 message 仍为枚举安全文案。商品用例与登录数据库回源已接入；业务切面不打印堆栈，公共全局异常处理入口负责：

- 4xx 业务拒绝统一由访问日志记录业务码，不额外刷 WARN；31 文档补齐了所有统一出口。
- 5xx BusinessException 打印一次服务端异常堆栈，保留 cause。
- 未知异常继续由全局处理器打印堆栈并返回安全 500。

其他既有组件的诊断日志未全部重写。不要在业务日志中自行拼接 SQL、参数和令牌；服务端异常日志也需要按部署权限管理。

访问日志已在 [31 文档](31-业务异常码与访问日志统一.md) 中补充 code 字段：MVC 与过滤器记录响应业务码，网关仅记录自身生成的业务码，正常转发的下游业务码通过 Trace ID 到下游日志关联。

共用 Logback 保留 [%X{traceId}]、UTF-8、按日期/100MB 滚动、保留 14 天/总量 2GB。控制台和文件各增加 8192 条有界 AsyncAppender 队列，neverBlock=true，避免调用线程等待日志落盘；事件入队时捕获 MDC。队列满会丢日志，关闭最多等待 3 秒，不保证异常断电时全部落盘。

默认 INFO 即可看到访问与关键业务日志。临时排查查询业务日志可在原配置中设置：

```yaml
logging:
  level:
    org.spring.shopcommon.logging.BizLogAspect: DEBUG
```

关闭业务切面可设 shop.logging.biz.enabled=false；不影响访问日志和 Trace ID。按类别调高 ServletAccessLog 或 org.spring.shopgateway.web.GatewayAccessLog 日志级别可关闭对应 INFO 访问日志。本次没有修改 application 配置文件或 POM 继承结构。

## 验证

新增真实 Spring AOP/事务代理测试：提交成功、提交失败、外层回滚延迟记录、捕获 Trace ID、可信用户身份、敏感输入不输出、恶意/错误 SpEL 不影响业务、异步方法误用拒绝。Servlet 测试验证拒绝状态与异步完成，网关测试验证 429 和外层连接异常 503，均检查一次性输出。

真实端到端脚本读取三个临时服务日志，验证同一 Trace ID 在网关/用户/商品访问日志中的关联和商品成功业务日志，并检查测试 Access Token 和密码不出现在日志中。异步日志输出有有界等待。脚本清理自身数据与临时进程。

2026-11-02 实际验证结果：

- `mvn -o -ntp -pl shop-product,shop-gateway -am package -Dspring.cloud.nacos.discovery.enabled=false`：BUILD SUCCESS。
- `mvn -o -ntp -pl shop-user -am package -Dspring.cloud.nacos.discovery.enabled=false`：BUILD SUCCESS。
- 按模块去重统计：common 41 项、gateway 89 项、user 62 项、product 36 项，共 228 项通过。包括本次新增 8 项日志测试（common 6、gateway 2）；重复执行的 common 不重复计数。
- Java 测试通过后，重新打包三个服务以包含最终 AsyncAppender 配置，再通过真实端到端进程验证该配置能够正常启动、捕获 MDC 和输出日志。
- 初次日志端到端检查因 Windows 重定向输出文件共享模式失败，业务请求已跑通；脚本改为允许共享读写后完整重跑。
- 最终端到端 passed=true，accessAndBusinessTraceLogs=true、accessTokenAbsentFromLogs=true。实际检查三服务相同 Trace ID、商品业务成功日志、登录业务日志，以及测试 Access Token/密码不在日志中；原有分类/商品/规格/销售状态/公开详情检查也通过。
- 临时服务和自身测试数据清理完成，未启动常驻业务服务。用户、商品、网关 JAR 已更新，启动/重启后即可加载。其他微服务需重新构建以打入新 common；其完整回归未在本次执行。
- Logback XML 和 PowerShell 语法检查通过；POM 和 application 配置文件未修改，OpenAPI 协议未改变。

Java 回归日志位于 target/logging-tests.log、target/logging-user-tests.log；最新打包日志 target/logging-package.log；端到端报告 target/category-smoke/result.json。端到端使用 SimpleDiscoveryClient，不代表 Nacos 集群或性能压测验收。异步队列溢出、进程崩溃丢日志及实际网络中断等故障场景未做生产规模验证。
