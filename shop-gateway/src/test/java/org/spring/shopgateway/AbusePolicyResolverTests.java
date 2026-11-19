package org.spring.shopgateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.spring.shopgateway.config.ProtectionProperties;
import org.spring.shopgateway.protection.AbusePolicy;
import org.spring.shopgateway.protection.AbusePolicyResolver;
import org.spring.shopgateway.protection.ConfiguredAbusePolicyResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/** 验证真实配置绑定、规则组合和启动失败行为，不连接 Redis 或 Nacos。 */
class AbusePolicyResolverTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(PolicyConfiguration.class)
            .withPropertyValues("gateway.protection.anti-abuse.requests-per-window=120");

    @Test
    void configurationControlsTheMatchingMethodsAndLimits() {
        context.withPropertyValues(
                "gateway.protection.anti-abuse.policies.register.methods[0]=POST",
                "gateway.protection.anti-abuse.policies.register.paths[0]=/api/v1/users",
                "gateway.protection.anti-abuse.policies.register.limit=7"
        ).run(application -> {
            assertThat(application).hasNotFailed();
            AbusePolicyResolver resolver = application.getBean(AbusePolicyResolver.class);
            assertThat(resolver.resolve(request("POST", "/api/v1/users", "user-api", "shop-user")))
                    .containsExactly(new AbusePolicy("all", 120), new AbusePolicy("register", 7));
            assertThat(resolver.resolve(request("GET", "/api/v1/users", "user-api", "shop-user")))
                    .containsExactly(new AbusePolicy("all", 120));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"/shop-future/api/v1/%75sers/?source=test", "/api/v1/users;source=test",
            "/shop-future/api/v1/users/", "/shop-future/api/v1/users;source=test"})
    void aliasesAndNewServicesUseTheMatchedRouteWithoutAHardcodedServiceList(String path) {
        context.withPropertyValues(
                "gateway.protection.anti-abuse.policies.register.paths[0]=/api/v1/users",
                "gateway.protection.anti-abuse.policies.register.limit=5"
        ).run(application -> assertThat(application.getBean(AbusePolicyResolver.class)
                .resolve(request("POST", path, "future-discovery", "shop-future")))
                .extracting(AbusePolicy::name).containsExactly("all", "register"));
    }

    @Test
    void matchingRulesStackInStableOrderAndOverlappingPathsDoNotDoubleCount() {
        context.withPropertyValues(
                "gateway.protection.anti-abuse.policies.specific.paths[0]=/api/v1/users",
                "gateway.protection.anti-abuse.policies.specific.paths[1]=/api/v1/users/**",
                "gateway.protection.anti-abuse.policies.specific.limit=5",
                "gateway.protection.anti-abuse.policies.specific.order=20",
                "gateway.protection.anti-abuse.policies.broad.paths[0]=/api/v1/**",
                "gateway.protection.anti-abuse.policies.broad.limit=10",
                "gateway.protection.anti-abuse.policies.broad.order=10",
                "gateway.protection.anti-abuse.policies.another.paths[0]=/api/v1/**",
                "gateway.protection.anti-abuse.policies.another.limit=10",
                "gateway.protection.anti-abuse.policies.another.order=10"
        ).run(application -> assertThat(application.getBean(AbusePolicyResolver.class)
                .resolve(request("POST", "/api/v1/users", "user-api", "shop-user")))
                .extracting(AbusePolicy::name).containsExactly("all", "another", "broad", "specific"));
    }

    @Test
    void routeRestrictionsAreIndependentOfPathMatching() {
        context.withPropertyValues(
                "gateway.protection.anti-abuse.policies.checkout.paths[0]=/api/v1/checkout/**",
                "gateway.protection.anti-abuse.policies.checkout.route-ids[0]=order-api",
                "gateway.protection.anti-abuse.policies.checkout.limit=2"
        ).run(application -> {
            AbusePolicyResolver resolver = application.getBean(AbusePolicyResolver.class);
            assertThat(resolver.resolve(request("POST", "/api/v1/checkout/quotes", "order-api", "shop-order")))
                    .extracting(AbusePolicy::name).containsExactly("all", "checkout");
            assertThat(resolver.resolve(request("POST", "/api/v1/checkout/quotes", "other-api", "shop-other")))
                    .extracting(AbusePolicy::name).containsExactly("all");
            assertThat(resolver.resolve(MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/checkout/quotes"))))
                    .extracting(AbusePolicy::name).containsExactly("all");
        });
    }

    @Test
    void noSpecialPoliciesStillEnforcesTheGeneralQuota() {
        context.run(application -> assertThat(application.getBean(AbusePolicyResolver.class)
                .resolve(request("GET", "/api/v1/products/1", "products", "shop-product")))
                .containsExactly(new AbusePolicy("all", 120)));
    }

    @Test
    void encodedSegmentsAreDecodedOnlyOnceAndTheRequestIsNotRewritten() {
        context.withPropertyValues(
                "gateway.protection.anti-abuse.policies.register.paths[0]=/api/v1/users",
                "gateway.protection.anti-abuse.policies.register.limit=5"
        ).run(application -> {
            ServerWebExchange exchange = request("POST", "/shop-user/api/v1/%2575sers/", "user", "shop-user");
            URI original = exchange.getRequest().getURI();
            assertThat(application.getBean(AbusePolicyResolver.class).resolve(exchange))
                    .extracting(AbusePolicy::name).containsExactly("all");
            assertThat(exchange.getRequest().getURI()).isEqualTo(original);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gateway.protection.anti-abuse.policies.rule.limit=0",
            "gateway.protection.anti-abuse.policies.rule.methods[0]=POSTT",
            "gateway.protection.anti-abuse.policies.rule.paths[0]=api/v1/users",
            "gateway.protection.anti-abuse.policies.rule.paths[0]=/api/**/users",
            "gateway.protection.anti-abuse.policies.rule.order=-1",
            "gateway.protection.anti-abuse.policies.rule.limt=5",
            "gateway.protection.anti-abuse.registrations-per-window=5"
    })
    void invalidAndObsoleteSettingsFailAtStartup(String invalidSetting) {
        context.withPropertyValues(
                "gateway.protection.anti-abuse.policies.rule.paths[0]=/api/v1/users",
                "gateway.protection.anti-abuse.policies.rule.limit=5", invalidSetting
        ).run(application -> assertThat(application).hasFailed());
    }

    @Test
    void missingPathsAndReservedNamesFailAtStartup() {
        context.withPropertyValues("gateway.protection.anti-abuse.policies.rule.limit=5")
                .run(application -> assertThat(application).hasFailed());
        context.withPropertyValues("gateway.protection.anti-abuse.policies.all.limit=5",
                        "gateway.protection.anti-abuse.policies.all.paths[0]=/**")
                .run(application -> assertThat(application).hasFailed());
    }

    private static ServerWebExchange request(String method, String path, String routeId, String service) {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.valueOf(method), URI.create(path)));
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR,
                Route.async().id(routeId).uri("lb://" + service).predicate(ignored -> true).build());
        return exchange;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProtectionProperties.class)
    static class PolicyConfiguration {
        @Bean
        AbusePolicyResolver policyResolver(ProtectionProperties properties) {
            return new ConfiguredAbusePolicyResolver(properties);
        }
    }
}
