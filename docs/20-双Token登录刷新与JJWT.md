# 双 Token 登录、刷新与 JJWT

2026-10-30 安全迁移：静态内部凭据已被逐请求内部 JWT 替代，下游请求统一验签，直连注册/登录也需内部凭据。旧阶段代码说明以 [24 文档](24-网关内部JWT与下游统一验签.md) 为准。
日期：2026-10-20。已在 shop-user 实现 MyBatis-Plus + BCrypt + JJWT 登录认证，接入现有 Gateway、公钥和双 Redis。当前路径按本次要求使用 `/api/auth/login`、`/api/auth/refresh`、`/api/auth/logout`；原 `/api/v1/auth/sessions` 等会话资源仍为后续设计，不把它们混称为本次已实现。

## 1. 文件与职责

| 文件 | 作用 |
|---|---|
| [JwtUtils](../shop-user/src/main/java/org/spring/shopuser/auth/JwtUtils.java) | JJWT RS256 签发/验签，只用本地密钥和时钟，不访问 Redis/数据库 |
| [AuthService](../shop-user/src/main/java/org/spring/shopuser/service/AuthService.java) | MyBatis-Plus 查询、BCrypt、双 Token 生成、刷新状态校验与轮换 |
| [AuthController](../shop-user/src/main/java/org/spring/shopuser/controller/AuthController.java) | 三个 POST 接口、DTO 校验、Result/no-store，不处理持久化和密码算法 |
| [RefreshTokenStore](../shop-user/src/main/java/org/spring/shopuser/auth/RefreshTokenStore.java) | 6380 主键/反向索引、原子 CAS 登录替换、一次性刷新和幂等登出 |
| [AuthInterceptor](../shop-user/src/main/java/org/spring/shopuser/auth/AuthInterceptor.java) | 受保护 Servlet API 的 Bearer 本地验证，身份保存 request attribute |
| [AuthWebConfiguration](../shop-user/src/main/java/org/spring/shopuser/auth/AuthWebConfiguration.java) | 在 `/api/**` 注册拦截器；内部权限接口继续沿用内部凭据 |
| [AuthConfiguration](../shop-user/src/main/java/org/spring/shopuser/auth/AuthConfiguration.java) | 有限读取 PKCS#8 私钥/X.509 公钥、验证 RSA2048+ 匹配密钥、装配 JwtUtils/Clock |
| [AuthRedisConfiguration](../shop-user/src/main/java/org/spring/shopuser/auth/AuthRedisConfiguration.java) | 独立 6380 连接资源，不干扰默认 6381 模板及 Boot 自动配置 |
| [AuthProperties](../shop-user/src/main/java/org/spring/shopuser/auth/AuthProperties.java) | 有效期、签发方/受众、密钥路径、Redis 地址和命名空间 |
| [UserLoginRequest](../shop-user/src/main/java/org/spring/shopuser/dto/UserLoginRequest.java) / [RefreshTokenRequest](../shop-user/src/main/java/org/spring/shopuser/dto/RefreshTokenRequest.java) | 严格输入契约；敏感字段 write-only，不生成含凭据的 toString |
| [AuthTokensVO](../shop-user/src/main/java/org/spring/shopuser/vo/AuthTokensVO.java) | 安全响应契约；正常 JSON 返回双 Token，toString 明确脱敏 |

## 2. 技术与依赖接入

shop-user 使用 `mybatis-plus-spring-boot3-starter:3.5.7` 和 `jjwt-api/impl/jackson:0.12.7`。Plus 替代该服务原 Starter，不能同时启用两套 MyBatis 自动装配。根 POM 仍默认 MyBatis 3.0.3，通过三项 Starter 坐标属性允许 user 单独覆盖，其他业务模块保持原坐标；父子继承结构不变。

UserEntity 增加 `@TableName("user_account")` 与自增 `@TableId`；UserMapper 继承 `BaseMapper<UserEntity>`，保留已有 XML、注册插入/查询及角色权限 SQL。认证使用 Lambda Query Wrapper，仅投影所需列；刷新查询不读取 password_hash。应用配置改为 `mybatis-plus.mapper-locations`，既有 Mapper XML 原样复用。本轮没有新增数据库表，也没有修改已执行 Flyway 脚本。

BCrypt 复用既有 PasswordEncoder。没有用户时也对虚拟哈希执行一次 matches，降低明显的用户名存在性耗时差异。账号不存在、密码不符、账号禁用均返回同一个 INVALID_CREDENTIALS，不向外提示具体原因。BCrypt 不占用数据库长事务。

## 3. Access Token

默认 30 分钟，RS256 非对称签名，载荷示例：

```json
{
  "sub": "42",
  "userId": "42",
  "username": "flour_demo",
  "iss": "flour-shop-user",
  "aud": ["flour-shop-api"],
  "iat": 1792500000,
  "exp": 1792501800,
  "jti": "每次签发独立随机UUID"
}
```

userId 使用字符串避免大整数精度丢失，同时保留 sub 兼容现有网关。JWT 只有签名，没有加密，username 是可读声明；不包含密码、角色或权限。私钥只由 user 读取，网关使用对应公钥，算法固定 RS256。

JwtUtils 拒绝错误签名/算法、错误签发方/受众、过长文本、缺少必要字段、sub/userId 不一致和内嵌角色权限。过期抛 `BusinessException(ACCESS_TOKEN_EXPIRED)`，其他无效凭据使用 INVALID_ACCESS_TOKEN，二者均是 HTTP 401。签名验证后的网关声明校验也使用结构化过期标记，返回相同的 ACCESS_TOKEN_EXPIRED，不解析异常 message 猜测过期。

**JWT 身份验证不查 Redis**。现有网关的 IP 防刷仍查询 6380，动态 RBAC 授权仍查询 6381；这两件事与 JWT 本地认证是不同职责，没有因为双 Token 重写/关闭原防护。因此不能声称 Redis 故障时整个网关仍然可用。

用户服务受保护 API 自身也通过 AuthInterceptor 验证 Bearer。验证后的身份在 `AuthInterceptor.IDENTITY` request attribute，不能直接信任外部 X-User-Id。网关转发保留 Authorization，服务端验证同一枚 JWT。

## 4. Refresh Token 与 Redis

使用 Java UUID.randomUUID() 的规范 UUID v4。默认登录后刷新资格最长 7 天，同一用户只保留一份有效刷新凭据。

| Key | Value | TTL |
|---|---|---|
| `auth:refresh_token:{42}` | 原始 UUID，严格符合本次要求 | 登录时 7 天；轮换继承剩余 TTL |
| `auth:refresh_lookup:<SHA256(UUID)>` | 用户 ID 字符串 `42` | 与主键一致 |

只有一个 UUID 且没有 userId 的刷新请求，无法直接拼出用户主键，所以必须增加反向索引。不会扫描 Redis，也不会让客户端提供 userId 决定刷新哪个账号。反向索引 Key 使用摘要，主键 Value 仍按要求存原始 UUID。

所有会话记录明确放到 **6380 安全 Redis（noeviction/AOF）**，而非会被 LFU 淘汰的 6381 业务缓存。内部 Resources 同时管理认证模板/工厂，不作为新的 StringRedisTemplate/RedisConnectionFactory 候选注册到容器，避免原权限失效器误连 6380。配置还检查认证端点与业务缓存不能相同。

### 登录替换

1. 验证用户名密码和启用状态。
2. 准备新的 Access JWT 和随机 Refresh UUID。
3. 读取旧主键，Lua CAS 确认未被其他登录改动后，设置新主键/索引并删旧索引。
4. Redis 保存成功才向客户端返回双 Token；竞争仅做有限 CAS 重读，不对不确定网络写入盲目重放。

再次登录使之前的 Refresh Token 失效，不代表之前的无状态 Access Token 立即失效。

### 刷新轮换

1. 根据 SHA256 反向索引定位用户。
2. Lua 校验主键中的原 UUID、反向索引用户和剩余 TTL。
3. 查询数据库确认账号仍启用，取得当前规范 username。
4. 准备新的 Access JWT/Refresh UUID。
5. Lua 原子比较旧值与索引，写新主键/索引并删除旧索引。
6. 并发只有一个请求能消费旧凭据，其余固定 401 INVALID_REFRESH_TOKEN。

轮换保留登录时的绝对刷新期限，不将每次刷新重新延长 7 天。最后一次合法签发的 Access Token 仍按它自己的 exp 失效。

### 登出

仅凭 Refresh Token 即可登出，不要求未过期 Access Token。Lua 只有在主键和索引仍对应这个 UUID 时才删除两者。旧凭据的延迟登出不能删新登录会话；重复登出幂等返回成功。**登出只撤销刷新资格，不立即撤销已签发 Access Token**。强制即时撤销将需要额外状态校验，和本次无状态认证要求不同。

当前脚本是单节点多 Key Lua。主键必须带 userId、反向索引按 UUID 查找，两者不在同一 Cluster slot；本实现不宣称可直接切换 Redis Cluster。Redis 故障/满内存导致存储失败时返回 503，不能伪装成密码错误或仍返回成功；AOF/noeviction 不等于已经部署主备高可用。

## 5. 接口调用

以下账号仅为格式示例，需要先通过现有注册接口创建实际账号。所有凭据使用 HTTPS 请求体/Authorization 传递，不放 URL，不写日志。响应带 Cache-Control: no-store / Pragma: no-cache。

### 登录

```http
POST http://localhost:8888/api/auth/login
Content-Type: application/json

{"username":"flour_demo","password":"FlourPass123!"}
```

```json
{
  "code":"OK",
  "message":"操作成功",
  "data":{
    "accessToken":"<RS256 JWT>",
    "refreshToken":"<UUID v4>",
    "tokenType":"Bearer",
    "expiresIn":1800,
    "refreshExpiresIn":604800,
    "userId":"42",
    "username":"flour_demo"
  },
  "traceId":"入口统一追踪ID"
}
```

### 访问受保护 API

```http
GET http://localhost:8888/api/v1/user
Authorization: Bearer <accessToken>
```

该现有测试端点最终故意返回业务 400，不是用户资料接口。要经网关到达它，仍需为其配置已允许的 RBAC 规则；测试脚本使用自己的随机规则命名空间。真实业务接口按自己的路由/权限契约调用。

### 刷新

```http
POST http://localhost:8888/api/auth/refresh
Content-Type: application/json

{"refreshToken":"<当前UUID>"}
```

不携带有效 Access Token 也可调用。成功返回相同 AuthTokensVO 结构，新 Access 和新 Refresh 都必须替换旧值。客户端应在收到明确的 401 ACCESS_TOKEN_EXPIRED 时单飞刷新，并最多重放一次原业务请求；多个并发请求共享同一次刷新结果。刷新失败 401 则重新登录，不能循环刷新。

旋转凭据已成功消费但网络丢失响应时，盲目重试旧值也会被拒绝，可能需要重新登录；本次不实现刷新响应幂等恢复/宽限窗口。新接口与旧设计中支持幂等重试的会话资源不是同一契约。

### 登出

```http
POST http://localhost:8888/api/auth/logout
Content-Type: application/json

{"refreshToken":"<当前UUID>"}
```

成功 HTTP 200，正文为 Result（data=null）。客户端清理本地双 Token。API 不接受 role、permissions、userId 等多余字段，严格 Jackson 返回 400。

| 错误 | HTTP/Result.code |
|---|---|
| 输入缺失/类型不正确/额外身份字段 | 400 VALIDATION_ERROR |
| 登录凭据错误或禁用用户 | 401 INVALID_CREDENTIALS |
| 用户服务本地 JWT 不合法 | 401 INVALID_ACCESS_TOKEN |
| Access Token 明确过期（网关/用户服务） | 401 ACCESS_TOKEN_EXPIRED |
| 刷新过期、伪造、已消费、被重新登录替换 | 401 INVALID_REFRESH_TOKEN |
| Redis/数据库不可用 | 503 DEPENDENCY_UNAVAILABLE |
| 原网关防刷/权限/依赖拒绝 | 保留原 429/403/503 契约 |

## 6. 网关与服务配置

Gateway 已增加 `/api/auth/**` 用户路由和三项 POST 白名单；白名单只跳过 Access JWT/RBAC，仍执行 Sentinel/IP 防刷。login 专项额度同时覆盖原规划路径和新 `/api/auth/login`；refresh/logout 使用独立专项额度。刷新/登出的凭据校验在 shop-user 完成，不能把白名单理解为无条件成功。

shop-user 配置在原 [application.yml](../shop-user/src/main/resources/application.yml)：

```yaml
shop:
  auth:
    access-ttl: 30m
    refresh-ttl: 7d
    clock-skew: 30s
    key-prefix: auth
    private-key-location: ${SHOP_USER_JWT_PRIVATE_KEY_LOCATION:file:../infra/local-secrets/jwt-private.pem}
    public-key-location: ${SHOP_GATEWAY_JWT_PUBLIC_KEY_LOCATION:file:../infra/local-secrets/jwt-public.pem}
    issuer: ${SHOP_GATEWAY_JWT_ISSUER:flour-shop-user}
    audience: ${SHOP_GATEWAY_JWT_AUDIENCE:flour-shop-api}
    redis:
      host: ${SHOP_GATEWAY_REDIS_HOST:127.0.0.1}
      port: ${SHOP_GATEWAY_REDIS_PORT:6380}
      password: ${SHOP_GATEWAY_REDIS_PASSWORD:shop_gateway_local_dev}
      timeout: 1s
```

默认密钥路径以 IDE 工作目录 `shop-user` 为基准；从项目根目录运行 JAR 时应提供绝对 file: URI。私钥只保存在忽略的 infra/local-secrets 或部署的秘密管理位置，不放入源码/POM。开发工具 [GenerateDevJwtKeys.java](../infra/security/GenerateDevJwtKeys.java) 会保留已有密钥，不覆盖：

```text
java infra/security/GenerateDevJwtKeys.java
docker compose -f shop-gateway/compose.yaml up -d redis-gateway redis-cache
```

然后按现有流程启动用户服务（其 Compose 管理 MySQL）和网关。更新代码后需重启对应服务。6381 默认模板及权限 outbox 配置仍保持原职责；JWT 公钥/issuer/audience 与网关保持一致。

OpenAPI 已补充三个实际实现路径，见 [openapi.json](openapi.json)，版本以 OpenAPI info.version 为准。旧会话资源依旧是设计项，未用它们替代本次用户指定的三个路径。

## 7. 验证

测试使用动态生成的临时 RSA 密钥，不读取实际开发/生产私钥；MySQL/Redis 使用真实容器。新增覆盖双 Token、MyBatis-Plus 投影、BCrypt、空/错凭据、禁用用户、TTL、单次刷新和 12 并发单次消费、重新登录替换、幂等/旧令牌登出、JWT 过期/错误签名/错误算法和声明、Redis 故障 503、原入口请求 ID。

2026-10-20 实际执行并通过：

```text
mvn -o -ntp -pl shop-user,shop-gateway -am package
```

构建结果 BUILD SUCCESS：shop-common 1 项、shop-user 62 项、shop-gateway 83 项，共 **146 项测试，失败/错误/跳过均为 0**。用户服务与网关可执行 JAR 均已打包。完整输出见 [构建日志](../target/dual-token-final-tests.log)。

随后使用 Java 17 执行真实双应用验收：

```powershell
./infra/security/Test-RedisFeignFlow.ps1 -JavaHome 'D:/developjava/Java/jdk-17'
```

脚本启动实际 user/gateway JAR，连接真实 MySQL、6380/6381 Redis，经网关完成注册、登录、刷新、旧凭据重放拒绝、携带新 JWT 访问受保护接口、OpenFeign 权限冷加载、outbox 失效、权限撤销后 403、登出及刷新资格撤销。认证 Redis 隔离、请求 ID 一致性也通过，结果见 [验收 JSON](../target/redis-feign-smoke/result.json)。脚本只清理自己的随机测试数据并停止自己启动的应用。

本次验收使用 SimpleDiscoveryClient 指向测试用户服务，没有验证实际 Nacos 注册/故障恢复；也没有执行生产压测、Redis 主备切换或 Cluster 验证。以上通过结果不代表这些场景已通过。

官方参考：[MyBatis-Plus 安装与 Starter 约束](https://baomidou.com/en/getting-started/install/)、[JJWT 签名与 verifyWith/parseSignedClaims](https://github.com/jwtk/jjwt/blob/main/README.adoc)。实现按本项目固定版本编译/验证，没有升级其他服务框架版本。
