package org.spring.shopgateway.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Set;

/**
 * Redis 权限文档的只读数据模型集合，由 RbacCodec 校验、RbacPermissionCache 消费。
 * 规则描述“接口需要什么权限”，UserGrant 描述“用户拥有什么权限”，二者分开更新。
 * 用户权限必须来自 shop-user 的数据库计算，不来自前端或 JWT；这里不保存数据库实体。
 */
public final class RbacModels {
    // 本类只作为嵌套模型的命名空间，不需要实例化外层对象。
    private RbacModels() { }

    /** 接口范围：methods 内任一方法和 paths 内任一路径同时满足即命中。 */
    public record Endpoint(
            // 限制合法的大写 HTTP 方法，避免拼错后配置看似存在、实际永远不生效。
            @NotEmpty Set<@Pattern(regexp = "GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|TRACE|CONNECT") String> methods,
            @NotEmpty @Size(max = 20) List<@NotBlank @Pattern(regexp = "/.*") String> paths) {
        public Endpoint {
            // 复制集合，构建后不可变；空值转空集合，随后由 @NotEmpty 提示配置不完整。
            methods = methods == null ? Set.of() : Set.copyOf(methods);
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    /**
     * 一条权限规则：id 在整份文档内唯一；routeIds 为空时不限制路由。
     * permissions 是要求的权限编码，match 决定这些编码需要全部满足还是任一满足。
     */
    public record Rule(
            @NotBlank @Size(max = 64) String id,
            @NotNull @Valid Endpoint endpoint,
            Set<@NotBlank String> routeIds,
            @NotEmpty @Size(max = 128) Set<@Pattern(regexp = "[A-Za-z0-9:_-]{1,128}") String> permissions,
            @NotNull Match match) {
        public Rule {
            // 路由限制可省略，权限集合不能省略；实际必填约束由 Codec 调用校验器执行。
            routeIds = routeIds == null ? Set.of() : Set.copyOf(routeIds);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }
    }

    /** ALL：拥有全部所需权限；ANY：拥有其中任意一个。多条命中规则之间仍是“且”。 */
    public enum Match { ALL, ANY }

    /** 完整接口规则文档；version 用于受控更新的旧版本比较，不是当前时间或 JWT 版本。 */
    public record RuleSet(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String version,
            @NotNull @Size(max = 1000) List<@NotNull @Valid Rule> rules) {
        // 固化规则列表，避免文档在本次匹配过程中被外部修改；重复 ID 由 Codec 进一步拒绝。
        public RuleSet { if (rules != null) rules = List.copyOf(rules); }
    }

    /**
     * 带 Redis TTL 的用户权限文档。enabled=false 或空权限是明确的拒绝结果，不是缓存缺失。
     * version 是文档标识；防止旧查询回填使用独立 epoch Key，不能把这两个版本概念混用。
     */
    public record UserGrant(
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String userId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String version,
            boolean enabled,
            @NotNull @Size(max = 256) Set<@Pattern(regexp = "[A-Za-z0-9:_-]{1,128}") String> permissions) {
        // 对集合做不可变复制；保留 null 以便 @NotNull 将损坏文档识别为故障，而非合法空权限。
        public UserGrant { if (permissions != null) permissions = Set.copyOf(permissions); }
    }

}
