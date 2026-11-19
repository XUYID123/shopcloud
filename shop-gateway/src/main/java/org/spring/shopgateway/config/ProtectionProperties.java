package org.spring.shopgateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 绑定 application.yml 中的 gateway.protection 配置。
 * 启动时校验数值和时间范围，避免使用无效的保护参数运行服务。
 *
 * @param sentinelQps 单个网关实例每秒允许通过的请求数，所有匹配路由共享此额度
 * @param antiAbuse Redis 防刷配置，通过嵌套校验检查其字段
 * @param trustedProxies 可信前置代理的 IP 或 CIDR 网段；默认不信任代理请求头
 */
@Validated
@ConfigurationProperties(value = "gateway.protection", ignoreUnknownFields = false)
public record ProtectionProperties(
        @DefaultValue("200") @Min(1) int sentinelQps,
        @DefaultValue @Valid @NotNull AntiAbuse antiAbuse,
        List<String> trustedProxies) {

    public ProtectionProperties {
        // 未配置时使用空列表；复制列表，防止绑定完成后被外部修改。
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }

    /**
     * 按“策略 + 客户端 IP”计数。连接同一 Redis 且使用相同前缀的网关共享额度。
     *
     * @param enabled 是否启用 Redis 防刷过滤器
     * @param requestsPerWindow 同一 IP 在窗口内的普通请求额度
     * @param policies 按名称定义专项规则，匹配条件和额度均来自配置，不与注册/登录代码绑定
     * @param window 滑动窗口长度，不是按自然分钟清零
     * @param violationsBeforeBan 触发临时封禁的超额次数；成功放行会清除对应策略的违规计数
     * @param banDuration 临时封禁时长；封禁期间的请求不会续期
     * @param redisTimeout 每一次策略检查的超时时间，不是整个请求的总超时时间
     * @param keyPrefix Redis 键前缀，用于隔离环境；限制字符以免干扰 Cluster 哈希标签
     */
    public record AntiAbuse(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("120") @Min(1) int requestsPerWindow,
            @DefaultValue("60s") @NotNull @DurationMin(seconds = 1) @DurationMax(days = 1) Duration window,
            @DefaultValue("3") @Min(1) int violationsBeforeBan,
            @DefaultValue("5m") @NotNull @DurationMin(seconds = 1) @DurationMax(days = 1) Duration banDuration,
            @DefaultValue("1s") @NotNull @DurationMin(millis = 1) @DurationMax(seconds = 10) Duration redisTimeout,
            @DefaultValue("gateway:abuse:v1") @Pattern(regexp = "[A-Za-z0-9:_-]{1,80}") String keyPrefix,
            Map<@Pattern(regexp = "[a-z][a-z0-9_-]{0,63}") String, @NotNull @Valid PolicyRule> policies) {
        public AntiAbuse {
            // 将专项策略表固化；未配置时只运行普通额度，不在执行器中硬编码注册/登录规则。
            policies = policies == null ? Map.of() : Map.copyOf(policies);
        }
    }

    /**
     * 一条专项策略的声明。不同条件之间为“且”，同一条件里的多个值为“或”。
     *
     * @param limit 当前 IP 在公共滑动窗口内可通过该策略的次数，必须明确配置
     * @param order 专项策略的执行顺序，数字小的先执行；相同顺序再按名称排序
     * @param methods HTTP 方法集合；空集合表示不限制方法，配置值使用大写
     * @param paths 至少一个路径模式，支持 Spring PathPattern 的通配符及路径变量
     * @param routeIds 可选的网关路由 ID 集合；空集合表示不限制路由
     */
    public record PolicyRule(
            @Min(1) int limit,
            @DefaultValue("0") @Min(0) int order,
            Set<@Pattern(regexp = "GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS|TRACE|CONNECT") String> methods,
            @NotEmpty List<@NotBlank @Pattern(regexp = "/.*") String> paths,
            Set<@NotBlank String> routeIds) {
        public PolicyRule {
            // 可选条件转为空集合表示“不限制”，paths 仍需通过 @NotEmpty 保证存在可匹配范围。
            methods = methods == null ? Set.of() : Set.copyOf(methods);
            paths = paths == null ? List.of() : List.copyOf(paths);
            routeIds = routeIds == null ? Set.of() : Set.copyOf(routeIds);
        }
    }
}
