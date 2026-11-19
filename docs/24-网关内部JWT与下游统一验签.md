# 网关内部 JWT 与下游统一验签

日期：2026-10-30。替代此前 X-Internal-Token 静态共享凭据。Header 名称保留，值改为每请求签发的 RS256 JWT；不再接受 SHOP_INTERNAL_TOKEN、gateway.auth.internal-token、shop.permissions.internal-token 或商品服务静态令牌。

## 1. 信任关系和流程

```text
外部 Access Token → 网关原 JWT 验证 → 原 Redis RBAC
    → 本次授权结果与权限快照摘要
    → 目标路由、StripPrefix/重写、实例选择
    → 网关私钥签发内部 JWT（45秒）
    → X-Internal-Token → 下游公共 Filter
    → 固定公钥验签 / issuer / typ / audience / exp / iat / jti / 请求绑定
    → 从签名载荷建立 userId、Principal、request attribute
    → 业务接口
```

前端继续仅携带 Authorization Bearer，不接触内部私钥，也不需要提交内部头。网关先删除外部 X-Internal-Token、身份头，再使用自己的授权结果签发。Authorization 与正常业务数据继续沿用原转发约定，角色/权限头不作为身份来源。

网关入口 WebFilter、Sentinel(-200)、Redis 防刷(-100)、认证/RBAC(-50) 不变。新 InternalTokenRelayFilter order=20000，在路由路径改写和 lb 解析之后、实际 Netty 转发之前签名，保证绑定的是下游实际收到的路径。RSA 签名在有界工作线程执行，不在 Netty 事件循环中做文件读取或签名计算。

## 2. 内部 JWT 载荷

```json
{
  "iss":"shop-gateway",
  "sub":"42",
  "userId":"42",
  "kind":"user",
  "permissionsDigest":"本次授权使用的权限集合排序编码后的SHA256摘要",
  "aud":["shop-product"],
  "iat":1793350000,
  "exp":1793350045,
  "jti":"独立UUID-v4",
  "method":"POST",
  "path":"/api/v1/admin/categories",
  "queryHash":"原始查询串的SHA256摘要"
}
```

签名算法固定 RS256，typ 固定 shop-internal+jwt。默认 TTL=45 秒，可配置 30～60 秒；下游拒绝生命周期超过 60 秒、未来 iat、缺少时间/主体/摘要/jti、错误 aud 或错误请求绑定的令牌，默认没有额外时钟宽限，部署应保持时钟同步。

aud 来自受控 Route 的 lb 服务名，不从客户端 Header/Query 获取。验证端只接受自己的 spring.application.name，且要求单一 audience。当前支持已有五个 lb 服务 shop-user/product/cart/order/pay；新增服务或改为 HTTP 静态路由需要明确增加目标映射，不会无凭据转发。

权限摘要由 RbacPermissionCache 在同一份用户授权快照上计算，GatewayAccessService 返回该摘要及 userId，不重新查询另一份可能变化的缓存。摘要用于审计/关联，不能还原成权限列表；下游信任网关已经完成的当前接口授权，资源归属、金额、订单状态等业务校验仍由服务负责。

## 3. 匿名和权限引导调用

所有下游请求都需要内部 JWT，登录/注册也不提供“缺少内部 JWT 就放行”的例外。网关白名单成功时签发 kind=anonymous、sub/userId=anonymous、空权限集合摘要的凭据。下游仅在本服务明确配置的公开 HTTP 方法/路径允许这种身份，不注入用户 ID。

user 公开入口：POST /api/v1/users、/api/auth/login、/api/auth/refresh、/api/auth/logout。商品 GET 分类/商品/SKU 查询路径已声明为未来公开入口；当前只实现分类创建，其它业务路径仍按阶段开发。

权限缓存冷加载发生在完整 RBAC 结果之前，不能要求调用者先拥有尚未查到的权限。网关在已完成 Access Token 验签后，签发独立 kind=permission-query 的最小能力凭据，绑定 aud=shop-user、GET /internal/v1/users/{该userId}/permissions。公共过滤器只允许它查询同一个用户的权限，禁止访问正常业务或别人的权限；正常 user/anonymous 凭据也不能用于 /internal/ 权限查询。不会保留静态令牌作为引导旁路。

## 4. 公共 starter 和所有服务接入

沿用 common 的 Boot 自动配置机制，未改变父 POM 继承。新 InternalAuthAutoConfiguration 已追加 imports；仅在 Servlet 环境强制注册，不提供 enabled=false 开关，缺少服务名/公钥即启动失败。

五个业务服务现有 common 依赖均自动启用。过滤器在 Trace Filter 后、MVC/业务前执行，覆盖 REQUEST/ASYNC/ERROR/FORWARD/INCLUDE。缺少、多值、静态旧值、错误签名、过期或 audience 不符返回 401 INVALID_INTERNAL_TOKEN；有效但用途越界返回 403。拒绝响应含同一 traceId，业务链不会执行。

下游一律清理外部 X-User-Id/X-User-Roles/X-User-Permissions，从 JWT 重建用户 ID 和 Principal；匿名没有身份头。可信对象保存到 InternalIdentity.ATTRIBUTE。不能将客户端 Header 或 DTO 中的 userId 当作验证结果。

ASYNC/ERROR 分派复用当前请求已经验过的身份，避免长请求在错误分派时因短凭据到期而掩盖原异常；FORWARD/INCLUDE 重新检查绑定路径，不能以内部转发绕过入口。过滤器不读取业务请求体、不查询 Redis。

原 user AuthInterceptor/AuthWebConfiguration 和 product CategoryAccessConfiguration 已移除。用户 Access JWT 的生成/解析工具保留，但受保护服务请求入口统一使用内部 JWT，裸 Access Token 不能直连服务替代内部凭据。

## 5. 文件与配置

| 文件 | 职责 |
|---|---|
| [InternalTokenSigner](../shop-gateway/src/main/java/org/spring/shopgateway/auth/InternalTokenSigner.java) | 网关独有，加载 PKCS8 私钥并签发普通/匿名/权限引导 JWT |
| [InternalTokenRelayFilter](../shop-gateway/src/main/java/org/spring/shopgateway/auth/InternalTokenRelayFilter.java) | 用路由绑定 audience，用最终请求绑定 method/path/query，转发前签名 |
| [GatewayAccessService](../shop-gateway/src/main/java/org/spring/shopgateway/auth/application/GatewayAccessService.java) | 继续执行原认证鉴权，产出可信身份和权限快照摘要 |
| [RbacPermissionCache](../shop-gateway/src/main/java/org/spring/shopgateway/auth/RbacPermissionCache.java) | 保留 Redis/Redisson/Feign，附带本次权限摘要，冷加载使用专用短 JWT |
| [InternalAuthAutoConfiguration](../shop-common/src/main/java/org/spring/shopcommon/internalauth/InternalAuthAutoConfiguration.java) | 所有下游的强制 Filter 与公钥验证器装配 |
| [InternalAuthenticationFilter](../shop-common/src/main/java/org/spring/shopcommon/internalauth/InternalAuthenticationFilter.java) | 验证、用途限制、身份头清洗重建、统一拒绝响应 |
| [InternalJwtVerifier](../shop-common/src/main/java/org/spring/shopcommon/internalauth/InternalJwtVerifier.java) | 纯本地 JJWT 公钥与声明校验 |
| [InternalIdentity](../shop-common/src/main/java/org/spring/shopcommon/internalauth/InternalIdentity.java) | 验证后的用户、用途、摘要和 jti |

从项目根目录生成独立开发密钥（已有文件不会覆盖）：

```powershell
java infra/security/GenerateInternalJwtKeys.java
```

默认生成到被忽略的 infra/local-secrets/gateway-internal-private.pem 和 gateway-internal-public.pem，独立于 user 用于签发 Access Token 的 jwt-private/public.pem。

网关原 application.yml：

```yaml
gateway:
  internal-jwt:
    private-key-location: ${SHOP_GATEWAY_INTERNAL_PRIVATE_KEY_LOCATION:file:../infra/local-secrets/gateway-internal-private.pem}
    ttl: 45s
```

下游只配置公钥，aud 直接取本服务名称，不可配置成别的服务来拓宽信任：

```yaml
spring:
  application:
    name: shop-user
shop:
  internal-auth:
    public-key-location: ${SHOP_GATEWAY_INTERNAL_PUBLIC_KEY_LOCATION:file:../infra/local-secrets/gateway-internal-public.pem}
    public-endpoints:
      - methods: [POST]
        paths: [/api/v1/users, /api/auth/login, /api/auth/refresh, /api/auth/logout]
```

user 仍用 YAML，其余服务仍用原 properties；没有改变配置文件架构。默认路径以模块工作目录为基准，根目录运行 JAR 请提供绝对 file URI。迁移需要同时部署网关和五个下游，再重启服务，不支持新旧静态凭据兼容期。更新后的 Test-CategoryFlow / Test-RedisFeignFlow 已改为网关注册并传入匹配的新公钥路径。

部署时网关只读挂载内部私钥，下游只读挂载内部公钥，不把整个 local-secrets 文件夹共享给服务，也不把私钥或开发夹具打入镜像/JAR。开发工作区同一 OS 用户可以读取本地文件，不构成实际部署的密钥隔离证明。user 自己的 Access Token 私钥属于另一个认证信任根；认证服务被攻破会影响身份签发，这不由内部 JWT 单独解决。

## 6. 安全边界

- jti 提供逐请求唯一标识，用于日志审计。**本次不保存消费状态，不宣称一次性防重放**；泄露的 JWT 在有效期内、相同服务/方法/路径/查询条件下仍可能被重放。请求体不参与签名，不应把它当作交易幂等键。
- 必须使用 TLS 与网络隔离保护传输。下游只有公钥时不能生成新的有效内部签名，但不意味着服务被攻破后无法滥用已有凭据或业务权限。
- 凭据代表短期授权快照，权限撤销不会立即撤回已签发 JWT；每次新外部请求仍走原 RBAC。没有引入 Redis JWT 黑名单。
- 下游不能把面向自己的凭据直接透传给另一个服务，aud/请求绑定会拒绝。当前已有的网关权限 Feign 已迁移；未来服务到服务的用户委托调用需要设计受控换票/委托流程，下游不获得网关私钥。
- 普通前端流程无需改变登录接口与外部 Access Token；直接访问 user 注册/登录端口的开发方式改为经网关访问。
- 认证失败不回显 JWT、原始解析异常或私钥。配置公钥/私钥错误使启动或请求失败，不自动降级成无鉴权。
- 当前单公钥配置未提供无缝多 key 轮换；切换密钥需协调部署。后续可按 kid 的受控公钥集合扩展，不能信任 Token 自带远程密钥 URL。

## 7. 验证记录

测试包括：错误签名/算法/受众/路径/方法/查询、过期和未来 iat、超长生命周期、无效摘要、旧静态令牌、多值头拒绝、身份头重建、匿名范围、权限查询范围、forward/错误分派隔离、逐请求 jti，以及原注册/刷新/RBAC/分类并发创建。

2026-10-30 实际执行结果：

| 验证 | 结果 |
|---|---|
| Java 17，`mvn -o -ntp package -Dspring.cloud.nacos.discovery.enabled=false` | BUILD SUCCESS；216 项测试，失败/错误/跳过均为 0 |
| 公共模块 / 缓存模块 / user / gateway / product / cart / order / pay | 分别 35 / 22 / 62 / 87 / 7 / 1 / 1 / 1 项通过 |
| 五个下游 ApplicationTests | 均断言 `internalAuthenticationFilterRegistration` 存在；WebFlux 网关断言不安装 Servlet 过滤器 |
| `infra/security/Test-CategoryFlow.ps1`，真实网关 + user + product + MySQL/双 Redis | 通过：匿名 401、普通用户 403、管理员创建 201、重名 409、非法输入 400、伪造头直连商品服务 401、traceId/Location 一致、发现路由重写后正常验签 |
| `infra/security/Test-RedisFeignFlow.ps1`，真实网关 + user + MySQL/双 Redis | 通过：网关注册/登录/刷新、旧 Refresh Token 重放拒绝、专用内部 JWT 的 OpenFeign 权限查询、MySQL 冷加载、Redis 回填、事务 outbox 失效后下一次 403、登出、认证 Redis 隔离及追踪日志 |
| 补齐中文注释后 `mvn -o -ntp package -DskipTests` | 最终重新编译打包成功；未重复执行功能测试 |
| 公共 JAR 及六个可执行服务包检查 | 公共 SPI/过滤器齐全；没有 PEM、测试夹具或已删除的旧鉴权类；InternalTokenSigner 仅在网关自身类目录存在，下游配置只有内部公钥 |

上述 HTTP 脚本使用随机端口、独立缓存命名空间和临时用户，结束后停止自身启动的进程并清理测试数据。回归日志为根目录 target/internal-jwt-verified-build.log；两份 HTTP 结果为 target/category-smoke/result.json、target/redis-feign-smoke/result.json。脚本不会将令牌写入报告。

联调使用 SimpleDiscoveryClient 的真实 lb 路由，并显式关闭 Nacos 注册；**没有验证实际 Nacos 集群、跨主机 TLS、部署密钥权限、线上容量及故障演练**，不将本地回归等同于这些验证。

验证设计依据：[RFC 8725](https://www.rfc-editor.org/info/rfc8725/) 的 audience/显式类型/不同 JWT 用途隔离，以及 [JJWT 官方签名验签说明](https://github.com/jwtk/jjwt)。
