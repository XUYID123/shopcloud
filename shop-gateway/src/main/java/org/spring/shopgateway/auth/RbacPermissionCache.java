package org.spring.shopgateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spring.shopgateway.auth.port.PermissionAuthorizer;
import org.spring.shopgateway.auth.policy.RbacPolicyEvaluator;
import org.redisson.api.RLockReactive;
import org.redisson.api.RedissonReactiveClient;
import org.spring.shopcommon.auth.PermissionCacheKeys;
import org.spring.shopcommon.auth.UserPermissionSnapshot;
import org.spring.shopcommon.result.Result;
import org.spring.shopcommon.trace.TraceContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.server.PathContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网关 RBAC 的基础设施适配器，由 GatewayAccessService 经 PermissionAuthorizer 端口调用。
 * <p>它回答两个问题：当前接口需要哪些权限？经过验证的用户是否拥有这些权限？
 * 规则和用户权限都从 6381 业务 Redis 读取，本类不持有 Caffeine 或其他本地权限缓存。
 * 用户缓存不存在时才通过 OpenFeign 查询 shop-user；Redis 故障不会被当作未命中回源。
 * <p>并发控制分两层：加载锁减少同一用户的重复查库，epoch 代号阻止失效后的旧结果回填。
 * 无权返回 false，由入口转换成 403；依赖故障抛出异常，由入口转换成 503。
 */
public class RbacPermissionCache implements PermissionAuthorizer {
    // 仅记录故障类型，不输出 JWT、内部凭据或整份用户权限文档。
    private static final Logger log = LoggerFactory.getLogger(RbacPermissionCache.class);
    // 冷加载前取得当前失效代号；Redis 丢失代号时会创建新 UUID，避免复用旧值。
    private static final RedisScript<String> EPOCH = RedisScript.of(new ClassPathResource("redis/rbac-user-epoch.lua"), String.class);
    // 回填时在 Redis 内原子检查 epoch 和锁所有者；二者仍有效才保存带 TTL 的权限。
    private static final RedisScript<Long> FILL = RedisScript.of(new ClassPathResource("redis/rbac-user-fill.lua"), Long.class);
    // 每次竞争都分配新的逻辑归属 ID；Reactor 请求可能共用同一物理线程，不能按当前线程重入。
    private static final AtomicLong LOAD_IDS = new AtomicLong();
    // 与业务模板使用同一 6381 实例；锁的加锁、所有者校验解锁均交给 Redisson。
    private final RedissonReactiveClient redisson;
    // 由配置类通过 Qualifier 注入 6381 模板，与 6380 防刷模板物理隔离。
    private final ReactiveStringRedisTemplate redis;
    // 提供 Redis 超时、总加载时限、缓存 TTL 和内部服务凭据等配置。
    private final GatewayAuthProperties properties;
    // 统一负责 JSON 编解码、字段约束及路径模式校验。
    private final RbacCodec codec;
    // 阻塞式声明 HTTP 客户端；只允许在下方指定的有界工作线程调用。
    private final UserPermissionClient client;
    // permissionFeignScheduler：隔离 Feign 的网络等待，保护 Netty I/O 线程。
    private final Scheduler scheduler;
    private InternalTokenSigner internalSigner;
    public void setInternalSigner(InternalTokenSigner signer) { this.internalSigner = signer; }

    /** 配置类集中装配依赖；构造时不连接 Redis、不发 HTTP 请求、不加载用户数据。 */
    public RbacPermissionCache(ReactiveStringRedisTemplate redis, GatewayAuthProperties properties, RbacCodec codec,
                               UserPermissionClient client, Scheduler scheduler, RedissonReactiveClient redisson) {
        this.redis = redis;
        this.properties = properties;
        this.codec = codec;
        this.client = client;
        this.scheduler = scheduler;
        this.redisson = redisson;
    }

    /** 整份接口规则的 Key，例如 shop:rbac:v1:rules；规则需要提前受控发布。 */
    public String rulesKey() { return properties.keyPrefix() + ":rules"; }
    /** 用户权限文档 Key，例如 shop:rbac:v1:user:{42}；值由冷加载产生并带 TTL。 */
    public String userKey(String userId) { return PermissionCacheKeys.user(properties.keyPrefix(), userId); }
    /** 失效代号 Key；用户服务更新权限后通过共用 Lua 更换此值。 */
    private String epochKey(String userId) { return PermissionCacheKeys.epoch(properties.keyPrefix(), userId); }
    /** 冷加载互斥 Key；Redisson 使用客户端/逻辑归属 ID 和有限租期，不保存业务权限。 */
    private String lockKey(String userId) { return PermissionCacheKeys.lock(properties.keyPrefix(), userId); }

    /**
     * 鉴权主入口：先找接口要求，再取得用户权限，两者比较后产生一个布尔结果。
     * @param userId 只能是 JWT 验证结果，不能从外部 X-User-Id 读取
     * @param method 当前 HTTP 方法，用于区分同路径的 GET、POST 等操作
     * @param path 已按网关统一规则处理的路径，仅用于匹配，不在这里重写转发路径
     * @param routeId 实际匹配到的路由 ID；规则中的 routeIds 为空表示不限制该条件
     * @param requestId 入口生成的关联编号，冷加载时继续传给 shop-user
     */
    public Mono<Boolean> authorize(String userId, String method, PathContainer path, String routeId, String requestId) {
        return authorizeWithSnapshot(userId, method, path, routeId, requestId).map(Decision::allowed);
    }
    @Override
    public Mono<Decision> authorizeWithSnapshot(String userId, String method, PathContainer path, String routeId, String requestId) {
        // defer：订阅后才开始检查；每次请求都实际读 Redis，不复用进程内权限快照。
        return Mono.defer(() -> redis.opsForValue().get(rulesKey()).timeout(properties.redisTimeout())
                // 缺少整份规则属于系统未准备好，不能解释成“全部接口开放”。
                .switchIfEmpty(Mono.error(new RbacUnavailableException("RBAC rules are not provisioned")))
                // JSON 解析和路径匹配交给并行计算线程，避免直接占用 Redis 的网络回调线程。
                .publishOn(Schedulers.parallel())
                // 规则语义由无 I/O 的纯策略类负责，本适配器仅负责取数据与加载协调。
                .map(json -> RbacPolicyEvaluator.matching(codec.read(json, RbacModels.RuleSet.class), method, path, routeId))
                // 未配置的受保护接口默认拒绝，且无需查询用户服务；不是“登录即可访问”。
                .flatMap(required -> required.isEmpty() ? Mono.just(new Decision(false, null))
                        // timeout 约束整个用户加载，包括竞争锁、重查 Redis 和 Feign 回源。
                        : user(userId, requestId).timeout(properties.loadTimeout())
                        .map(grant -> new Decision(RbacPolicyEvaluator.allows(grant, required),
                                org.spring.shopcommon.internalauth.InternalJwtProtocol.permissionsDigest(grant.permissions())))));
    }

    /** 单纯读取 Redis 用户文档；返回 Mono.empty 才代表缓存不存在，错误保持错误语义。 */
    private Mono<RbacModels.UserGrant> readUser(String userId) {
        return redis.opsForValue().get(userKey(userId)).timeout(properties.redisTimeout())
                // 命中后的 JSON 解析在计算线程执行；格式和权限编码约束仍需校验。
                .publishOn(Schedulers.parallel()).map(json -> {
                    var grant = codec.read(json, RbacModels.UserGrant.class);
                    // Key 指向 42 时，正文也必须是 42；缓存串号不能被当作合法授权。
                    if (!userId.equals(grant.userId())) throw new RbacUnavailableException("RBAC user key mismatch");
                    return grant;
                });
    }

    /** 取得用户权限：先查缓存，未命中再竞争 Redis 加载锁；不在 JVM 中缓存用户结果。 */
    private Mono<RbacModels.UserGrant> user(String userId, String requestId) {
        // 只有真正的缓存不存在才回源；连接错误、损坏文档不会触发无界数据库回源。
        return readUser(userId).switchIfEmpty(Mono.defer(() -> {
            // ID 由 JVM 内所有权限执行器共享计数器生成；不同 Redisson 客户端还有各自的 clientId 隔离。
            long ownerId = LOAD_IDS.incrementAndGet();
            RLockReactive lock = redisson.getLock(lockKey(userId));
            // 显式 5 秒租期（默认 loadTimeout=4 秒），不启用看门狗无限续期。
            // waitTime=0：未获锁立即返回，外层用异步延迟重读缓存，避免逐个拿锁后重复回源。
            return Mono.usingWhen(lock.tryLock(0, properties.loadTimeout().plusSeconds(1).toMillis(),
                                    TimeUnit.MILLISECONDS, ownerId).timeout(properties.redisTimeout())
                            .filter(Boolean::booleanValue).map(acquired -> ownerId),
                    // 获锁后再读缓存。ownerField 与固定版本 Redisson 的 Hash 字段约定一致，供原子回填使用。
                    id -> readUser(userId).switchIfEmpty(Mono.defer(() ->
                            coldLoad(userId, requestId, redisson.getId() + ":" + id))),
                    // 正常、异常、取消均由 usingWhen 订阅异步解锁；不在 doFinally 中自行 subscribe。
                    id -> unlock(lock, id), (id, error) -> unlock(lock, id), id -> unlock(lock, id))
                    // 未拿到锁才走此分支。Redis 异常仍作为错误抛出，不能绕过锁直接查库。
                    .switchIfEmpty(Mono.defer(() -> Mono.delay(Duration.ofMillis(25))
                            .then(Mono.defer(() -> user(userId, requestId)))));
        }));
    }

    /** 持锁冷加载：读取代号 → Feign 查库 → 校验快照 → 带代号/所有者校验的原子回填。 */
    private Mono<RbacModels.UserGrant> coldLoad(String userId, String requestId, String owner) {
        // 先记录本次查询所依据的 epoch。失效脚本会更换它，旧查询不能跨越失效继续写入。
        return redis.execute(EPOCH, List.of(epochKey(userId)), List.of(UUID.randomUUID().toString())).single()
                // fromCallable 才把同步 Feign 调用包装成惰性任务；不能提前调用再放进 Mono.just。
                .timeout(properties.redisTimeout()).flatMap(epoch -> Mono.fromCallable(() -> {
                    // 显式覆盖隔离线程的同步调用边界，公共 Feign 拦截器从 MDC 自动设置两种追踪头。
                    // try-with-resources 在成功/异常后恢复工作线程；不是将请求 MDC 永久挂在线程上。
                    try (var ignored = TraceContext.open(requestId)) {
                        if (internalSigner == null) throw new IllegalStateException("Internal JWT signer is required");
                        return client.permissions(userId, internalSigner.permissionQuery(userId), requestId);
                    }
                })
                        // 阻塞 HTTP 调用确实运行在专用有界线程，不因为返回 Mono 就自动变成非阻塞客户端。
                        .subscribeOn(scheduler)
                        // 统一响应必须 OK、正文存在且用户 ID 一致，再转成 Redis 文档。
                        .map(result -> document(userId, result))
                        // Lua 的 KEYS 依次为 epoch、用户文档、Redisson Hash 锁；ARGV 最后一个为锁归属字段。
                        .flatMap(grant -> redis.execute(FILL, List.of(epochKey(userId), userKey(userId), lockKey(userId)),
                                        List.of(epoch, codec.write(grant), Long.toString(properties.cacheTtl().toMillis()), owner))
                                .single().timeout(properties.redisTimeout())
                                // 0 表示代号或锁已变化：不写缓存，也不使用刚取到的旧权限放行本次请求。
                                .flatMap(written -> written == 1 ? Mono.just(grant)
                                        : Mono.error(new RbacUnavailableException("RBAC changed during cold load; retry required")))));
    }

    /** 将内部服务返回值转换为缓存文档；文档 version 是新 UUID，不是数据库单调版本。 */
    private RbacModels.UserGrant document(String userId, Result<UserPermissionSnapshot> result) {
        // 防止服务异常返回、空正文或其他用户权限混入当前用户的缓存。
        if (result == null || !"OK".equals(result.getCode()) || result.getData() == null
                || !userId.equals(result.getData().userId())) {
            throw new RbacUnavailableException("Invalid permission service response");
        }
        var value = result.getData();
        // 对即将写入的用户权限再次执行大小/格式校验；明确的禁用或空集合可正常缓存并产生 403。
        return codec.validate(new RbacModels.UserGrant(userId, UUID.randomUUID().toString(), value.enabled(), value.permissions()));
    }

    /** 按锁所有者释放资源；清理失败仅记录故障，不能掩盖之前的鉴权/Feign 原始异常。 */
    private Mono<Void> unlock(RLockReactive lock, long ownerId) {
        // 显式归属 ID 跨线程解锁；禁止 forceUnlock，避免旧请求删除其他加载者的锁。
        return lock.unlock(ownerId).timeout(properties.redisTimeout()).onErrorResume(error -> {
                    // 锁有租期；取消或连接故障时不无限等待，也不以清理错误覆盖原始失败。
                    log.warn("Permission load lock cleanup unavailable; type={}", error.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}
