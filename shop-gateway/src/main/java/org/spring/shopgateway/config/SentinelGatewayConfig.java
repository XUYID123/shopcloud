package org.spring.shopgateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import com.alibaba.csp.sentinel.config.SentinelConfig;
import jakarta.annotation.PostConstruct;
import org.spring.shopcommon.protection.NacosQpsRule;
import org.spring.shopgateway.web.GatewayResponses;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 注册 Sentinel 网关适配器、容量规则和统一的限流响应。
 * Sentinel 负责单实例的容量保护，按 IP 的共享额度由 Redis 防刷层负责。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProtectionProperties.class)
// Sentinel 首次访问时会缓存通信参数，须先让 Starter 完成控制台地址等配置。
@DependsOn("com.alibaba.cloud.sentinel.custom.SentinelAutoConfiguration")
public class SentinelGatewayConfig {
    private final ProtectionProperties properties; // 启动加载的容量阈值，不是每请求从控制台同步读取。
    private final GatewayResponses responses; // 生成带入口 traceId 的 common.Result 限流正文。

    /** 只注入阈值和响应工厂；真正加载 Sentinel 本地规则由 initialize 完成。 */
    public SentinelGatewayConfig(ProtectionProperties properties, GatewayResponses responses) {
        this.properties = properties;
        this.responses = responses;
    }

    /**
     * 启动时装载本地默认规则。启用 Nacos 后，在启动完成前替换为持久化规则。
     */
    @PostConstruct
    public void initialize() {
        // 将客户端标记为网关类型，使 Sentinel 控制台展示网关规则页面。
        System.setProperty(SentinelConfig.APP_TYPE_PROP_KEY, "1");
        // 同一 API 分组覆盖所有匹配路由的请求，包括带服务名前缀的发现路由。
        // 此处的 PREFIX 策略使用路径模式匹配，/** 才能覆盖各层路径。
        GatewayApiDefinitionManager.loadApiDefinitions(Set.of(new ApiDefinition("gateway.capacity")
                .setPredicateItems(Set.of(new ApiPathPredicateItem().setPattern("/**")
                        .setMatchStrategy(SentinelGatewayConstants.PARAM_MATCH_STRATEGY_PREFIX)))));
        // 针对分组而非单条路由设限，避免每条路由分别获得一份容量额度。
        applyCapacity(properties.sentinelQps());
        // 被 Sentinel 拒绝时直接返回 429；Retry-After 单位为秒，响应体沿用 common 的格式。
        GatewayCallbackManager.setBlockHandler((exchange, throwable) -> ServerResponse.status(429)
                .contentType(MediaType.APPLICATION_JSON).header("Retry-After", "1")
                .bodyValue(responses.body(exchange, "RATE_LIMITED", "请求过多，请稍后重试")));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "gateway.sentinel.nacos-rules-enabled", havingValue = "true")
    NacosQpsRule gatewayCapacityRule(Environment environment) throws Exception {
        return NacosQpsRule.open(
                environment.getRequiredProperty("spring.cloud.nacos.discovery.server-addr"),
                environment.getProperty("spring.cloud.nacos.discovery.namespace", ""),
                environment.getProperty("spring.cloud.nacos.discovery.username", ""),
                environment.getProperty("spring.cloud.nacos.discovery.password", ""),
                "shop-gateway-capacity.json", SentinelGatewayConfig::applyCapacity);
    }

    private static void applyCapacity(int qps) {
        GatewayRuleManager.loadRules(Set.of(new GatewayFlowRule("gateway.capacity")
                .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME)
                .setCount(qps).setIntervalSec(1)));
    }

    /** 把 Sentinel 自带适配器加入 GlobalFilter 链；-200 先于防刷和认证，不与 WebFilter 混排。 */
    @Bean
    public SentinelGatewayFilter sentinelGatewayFilter() {
        // 路由过滤器按 order 从小到大进入：-200 先于 Redis 防刷层的 -100。
        return new SentinelGatewayFilter(-200);
    }

    /**
     * 将 Sentinel 的拦截异常交给网关专用处理器，调用上面注册的响应回调。
     * @Order(-2) 是 Web 异常处理器的排序，不是 Sentinel GlobalFilter 的执行顺序。
     */
    @Bean
    @Order(-2)
    public SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler(
            ObjectProvider<List<ViewResolver>> resolvers, ServerCodecConfigurer codecs) {
        return new SentinelGatewayBlockExceptionHandler(resolvers.getIfAvailable(Collections::emptyList), codecs);
    }
}
