package org.spring.shopgateway.protection;

import org.spring.shopgateway.config.ProtectionProperties;
import org.spring.shopgateway.web.GatewayRequestPath;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 策略模式的配置实现：启动时把规则表编译为不可变匹配器，请求期间只匹配和选择。
 * 新增普通路径规则只需调整 YAML，无需为每个接口增加分支或创建一个 Java 策略类。
 */
@Component
public class ConfiguredAbusePolicyResolver implements AbusePolicyResolver {
    private final AbusePolicy generalPolicy; // 所有匹配路由的请求都要先检查的 all 普通额度。
    private final List<CompiledRule> rules; // 启动时编译的防刷配置，不是用户权限缓存；修改配置需重启。

    /** 绑定配置后编译一次路径，并按 order/策略名称排序，避免每次请求重新组织配置。 */
    public ConfiguredAbusePolicyResolver(ProtectionProperties properties) {
        var settings = properties.antiAbuse();
        // 普通额度始终第一项，不会因专项规则顺序变化、未匹配或配置为空而跳过。
        generalPolicy = new AbusePolicy("all", settings.requestsPerWindow());
        PathPatternParser parser = new PathPatternParser();
        rules = settings.policies().entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, ProtectionProperties.PolicyRule>>comparingInt(
                                entry -> entry.getValue().order())
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> compile(entry.getKey(), entry.getValue(), parser))
                .toList();
    }

    /** 将请求转换成执行计划：all 永远第一，后面追加所有匹配的专项策略。 */
    @Override
    public List<AbusePolicy> resolve(ServerWebExchange exchange) {
        // 只使用框架路由属性、实际方法和统一路径，不能由外部头声明“我属于哪个服务”。
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        String routeId = route == null ? null : route.getId();
        String method = exchange.getRequest().getMethod().name();
        PathContainer path = GatewayRequestPath.resolve(exchange);
        List<AbusePolicy> selected = new ArrayList<>();
        selected.add(generalPolicy);
        for (CompiledRule rule : rules) {
            if (rule.matches(method, path, routeId)) {
                // 一条策略有多个路径同时命中时仍只加入一次；多个策略命中则全部叠加。
                selected.add(rule.policy());
            }
        }
        // 固化本次计划，执行器顺序检查时不受后续列表修改影响。
        return List.copyOf(selected);
    }

    /** 把一条配置转换成额度对象和匹配器；保留名字用于 Redis 桶和错误定位。 */
    private static CompiledRule compile(String name, ProtectionProperties.PolicyRule definition,
                                        PathPatternParser parser) {
        // all 是普通额度的保留名称，不能用专项配置覆盖它。
        if (name.equals("all")) {
            throw new IllegalArgumentException("Policy name 'all' is reserved for the general quota");
        }
        try {
            List<PathPattern> paths = definition.paths().stream()
                    .map(ConfiguredAbusePolicyResolver::trimTrailingSlashes).map(parser::parse).toList();
            return new CompiledRule(new AbusePolicy(name, definition.limit()), definition.methods(),
                    paths, definition.routeIds());
        } catch (IllegalArgumentException exception) {
            // 带上策略名，让错误在启动时暴露，避免非法路径模式悄悄失去保护。
            throw new IllegalArgumentException("Invalid path pattern in policy '" + name + "'", exception);
        }
    }

    /** 路径配置去尾斜杠但保留根目录，与请求解析工具的匹配语义一致。 */
    private static String trimTrailingSlashes(String path) {
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /** 匹配条件采用只读快照，路径模式在启动时完成编译，不在每次请求中重新编译。 */
    private record CompiledRule(AbusePolicy policy, Set<String> methods, List<PathPattern> paths,
                                Set<String> routeIds) {
        /** 不同条件为“且”；方法/路由空集合不限制，路径列表命中任意一个即可。 */
        boolean matches(String method, PathContainer path, String routeId) {
            return (methods.isEmpty() || methods.contains(method))
                    && (routeIds.isEmpty() || (routeId != null && routeIds.contains(routeId)))
                    && paths.stream().anyMatch(pattern -> pattern.matches(path));
        }
    }
}
