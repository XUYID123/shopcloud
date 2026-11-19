package org.spring.shopcommon.auth;

/**
 * 网关与用户服务共用的 Redis Key/内部请求头契约，不依赖任何 Redis 客户端。
 * 网关按这些 Key 加锁和回填，用户服务按相同 Key 失效；命名不同会导致缓存无法正确删除。
 * 用户 ID 的花括号让同一用户的多键 Lua 处于同一 Cluster slot，不代表当前已部署 Cluster。
 */
public final class PermissionCacheKeys {
    // 内部 JWT 头：网关入口删除外部值，网关转发/Feign 按请求签发。
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    // 纯工具类，不创建对象，也不存储用户、Token 或 Redis 连接。
    private PermissionCacheKeys() { }

    /** 用户 JSON 文档 Key，拥有业务缓存 TTL；prefix 隔离环境，userId 来自可信验证结果。 */
    public static String user(String prefix, String userId) { return prefix + ":user:{" + userId + "}"; }
    /** 随机失效代号 Key，每次权限失效更换，用于发现跨越失效的旧查询。 */
    public static String epoch(String prefix, String userId) { return user(prefix, userId) + ":epoch"; }
    /** 带租期的冷加载互斥锁 Key；Redisson Hash 保存客户端与逻辑归属 ID 的重入计数。 */
    public static String lock(String prefix, String userId) { return user(prefix, userId) + ":lock"; }
}
