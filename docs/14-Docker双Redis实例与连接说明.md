# Docker 双 Redis 实例与连接说明

配置日期：2026-10-18。两个 Redis 使用独立容器、内存、密码、数据卷和端口，均为本地单节点部署。镜像沿用项目当前的 `redis:8.2.10-alpine`。

2026-10-19 当前 JWT 本地验签，RBAC 只使用 6381 Redis 缓存，未命中通过 OpenFeign 调用 shop-user 查库；已移除 Caffeine 与权限 Pub/Sub。6380 继续独立负责 IP 防刷；当前实现见 [权限冷加载说明](17-Redis权限冷加载与OpenFeign.md)。

## 1. 连接参数与职责

| 项目 | 安全 Redis | 业务缓存 Redis |
|---|---|---|
| 用途 | 限流/防刷计数、临时封禁；可扩展独立的会话/令牌撤销状态 | 可重建业务缓存、RBAC 权限缓存和加载锁/失效代号 |
| Compose 服务名 | `redis-gateway`，沿用原名 | `redis-cache` |
| 容器名 | `flour-shop-gateway-redis-gateway-1` | `flour-shop-gateway-redis-cache-1` |
| IDEA 启动的应用连接地址 | `127.0.0.1:6380` | `127.0.0.1:6381` |
| Redis 用户名 | `default`，客户端也可只提供密码 | `default`，客户端也可只提供密码 |
| 本地默认密码 | `shop_gateway_local_dev` | `shop_cache_local_dev` |
| 推荐使用的逻辑数据库 | `0` | `0` |
| Redis 数据内存上限 | `128 MB` | `256 MB` |
| 淘汰策略 | `noeviction` | `allkeys-lfu` |
| AOF | 开启 | 关闭 |
| RDB 自动快照 | 保留 Redis 默认设置 | 关闭 |
| 数据卷 | `flour-shop-gateway_redis-gateway-data`，沿用原卷 | `flour-shop-gateway_redis-cache-data` |

网关 [application.yml](../shop-gateway/src/main/resources/application.yml) 当前连接安全实例 `6380`。Sentinel 继续处理网关容量保护，安全 Redis 承担现有防刷状态与计数。

JWT 验签和 RBAC 已落地，登录签发、会话和黑名单仍需在对应业务代码中实现；具体商品缓存功能在实现商品业务时接入。

两个实例的数据库编号都可以用 0，因为它们是两个不同 Redis 进程。同一 Redis 进程中的两个逻辑数据库会共用内存及淘汰策略，本次按实例隔离，缓存淘汰不会清理安全实例中的键。

## 2. 为什么两边策略不同

安全实例的封禁、计数、会话等状态需要保留，因此开启 AOF，并用 `noeviction` 避免内存满时自动删掉这些状态。达到内存限制后写入可能失败；现有网关会把防刷检查失败处理为 503，停止转发。

业务缓存可以回源数据库重建，因此采用 `allkeys-lfu`：内存达到限制时可以淘汰低频访问的键。缓存还应按业务设置 TTL。缓存实例不存需要可靠保留的会话、黑名单、业务唯一凭据或唯一库存数据。

业务缓存关闭 AOF 和 RDB，实例重建或重启时可以失去缓存。虽然有独立数据卷，也不能把该卷理解成缓存已持久化；启用持久化策略与挂载目录是两件事。安全实例的 AOF 提高重启恢复能力，不代表每次写入都同步落盘或已经具备主从高可用。

## 3. 配置位置与启动方式

统一配置在 [shop-gateway/compose.yaml](../shop-gateway/compose.yaml)。继续保留原 Compose 项目名、`redis-gateway` 服务名及其数据卷，复用已有防刷数据。

从项目根目录执行：

```powershell
docker compose -f shop-gateway/compose.yaml up -d --wait redis-gateway redis-cache
docker compose -f shop-gateway/compose.yaml ps
```

两个服务均应显示 `healthy`。网关原有 Spring Boot Compose 支持也会启动或复用这两个服务；网关工作目录仍使用 `shop-gateway`，默认文件仍为 `compose.yaml`。

两个容器设置 `restart: unless-stopped`，在 Docker 引擎运行时自动恢复异常退出的容器。正常停止某个容器后，它会保持停止，直到再次启动。

`org.springframework.boot.ignore=true` 用于跳过自动连接参数推导，应用通过 YAML 显式连接 6380；该标签不阻止 Compose 启动容器。网关使用 `start-only`，退出网关不会顺带停止这两个 Redis。

如需分别停止：

```powershell
docker compose -f shop-gateway/compose.yaml stop redis-gateway
docker compose -f shop-gateway/compose.yaml stop redis-cache
```

以上停止操作保留数据卷。没有在本次操作中清空 Redis 或删除原安全数据卷。

## 4. 环境变量

| 变量 | 默认值 | 使用位置 |
|---|---|---|
| `SHOP_GATEWAY_REDIS_HOST` | `127.0.0.1` | 网关应用连接安全 Redis 的地址 |
| `SHOP_GATEWAY_REDIS_PORT` | `6380` | 安全实例宿主机映射端口及网关连接端口 |
| `SHOP_GATEWAY_REDIS_PASSWORD` | `shop_gateway_local_dev` | 安全实例 requirepass 和网关连接密码 |
| `SHOP_CACHE_REDIS_PORT` | `6381` | 缓存实例宿主机映射端口 |
| `SHOP_CACHE_REDIS_PASSWORD` | `shop_cache_local_dev` | 缓存实例 requirepass |
| `SHOP_GATEWAY_COMPOSE_ENABLED` | `true` | 网关是否通过 Boot Compose 启动本地基础设施 |
| `SHOP_GATEWAY_COMPOSE_FILE` | `compose.yaml` | 网关启动时使用的 Compose 路径 |

安全实例保留原有 `SHOP_GATEWAY_REDIS_*` 变量名称，后续鉴权服务也应配置成连接同一个实际地址和密码。修改密码或端口后，需要让容器和应用都应用对应配置。绑定地址目前仅为 `127.0.0.1`。

应用若以后也运行在同一 Compose 网络的容器中，应使用 `redis-gateway:6379` 或 `redis-cache:6379`；容器自己的 localhost 不代表宿主机 Redis。

## 5. 业务服务接入缓存的写法

对于只使用业务缓存的服务，可按需引入父 POM 已管理版本的依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

例如 product 当前使用 properties，可以在实现缓存功能时添加如下配置：

```properties
spring.data.redis.host=${SHOP_CACHE_REDIS_HOST:127.0.0.1}
spring.data.redis.port=${SHOP_CACHE_REDIS_PORT:6381}
spring.data.redis.password=${SHOP_CACHE_REDIS_PASSWORD:shop_cache_local_dev}
spring.data.redis.database=0
spring.data.redis.connect-timeout=1s
spring.data.redis.timeout=1s
```

本次没有把缓存依赖和上述客户端配置自动加入所有业务服务。需要缓存的模块按需接入，再编写缓存读写和失效逻辑。

一个服务如果同时访问安全 Redis 和业务缓存 Redis，需要两个明确命名的连接工厂和模板，并通过 `@Qualifier` 等方式选择；不能重复声明两份 `spring.data.redis` 期待得到两个客户端。

## 6. 连接检查与实测

以下命令从容器环境读取密码，不需要把密码直接写进命令参数：

```powershell
docker compose -f shop-gateway/compose.yaml exec -T redis-gateway sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli PING'
docker compose -f shop-gateway/compose.yaml exec -T redis-cache sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli PING'
```

两条均应返回 `PONG`。Redis 管理工具使用第 1 节的宿主机地址、端口、密码和数据库 0 即可。

本次已经验证：

- 两个容器均为 healthy，宿主机端口分别为 6380、6381。
- 两边认证后 PING 成功，未认证请求被拒绝。
- 实际内存上限、淘汰策略、AOF/RDB 设置与上表一致。
- 使用随机临时键在两个实例写入不同值，读取结果互不影响；测试后仅删除本次测试键。
- 两个容器挂载不同数据卷，安全实例继续使用原有卷。
- 网关真实 Redis 防刷集成测试 7 项通过，覆盖共享额度、注册入口、防伪造 IP、封禁与过期等行为；两个限流器向安全实例发起 40 个并发检查，额度为 5 时恰好放行 5 个。
