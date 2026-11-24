package org.spring.shoporder.admission;

import com.alibaba.csp.sentinel.cluster.TokenResultStatus;
import com.alibaba.csp.sentinel.cluster.client.DefaultClusterTokenClient;
import com.alibaba.csp.sentinel.cluster.client.config.ClusterClientAssignConfig;
import com.alibaba.csp.sentinel.cluster.client.config.ClusterClientConfig;
import com.alibaba.csp.sentinel.cluster.client.config.ClusterClientConfigManager;
import com.alibaba.csp.sentinel.config.SentinelConfig;
import com.alibaba.csp.sentinel.init.InitExecutor;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Semaphore;

public final class ClusterOrderAdmission {
    private final DefaultClusterTokenClient tokens;
    private final Semaphore requests = new Semaphore(32);

    ClusterOrderAdmission(DefaultClusterTokenClient tokens) {
        this.tokens = tokens;
    }

    public void acquire() {
        if (!requests.tryAcquire()) throw unavailable();
        try {
            var result = tokens.requestToken(OrderTokenServer.CREATE_FLOW_ID, 1, false);
            if (result == null || result.getStatus() == null) throw unavailable();
            int status = result.getStatus();
            if (status == TokenResultStatus.OK) return;
            if (status == TokenResultStatus.BLOCKED) throw new BusinessException(ErrorCode.RATE_LIMITED);
            // Sentinel's local fallback can admit more traffic than the shared QPS budget.
            throw unavailable();
        } finally {
            requests.release();
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = {"shop.order.m3.enabled", "shop.order.admission.cluster.enabled"}, havingValue = "true")
    static class ConfigurationBeans {
        @Bean(initMethod = "start", destroyMethod = "stop")
        DefaultClusterTokenClient orderTokenClient(
                @Value("${shop.order.admission.cluster.host}") String host,
                @Value("${shop.order.admission.cluster.port}") int port,
                @Value("${shop.order.admission.cluster.timeout-ms}") int timeoutMs) {
            if (host.isBlank() || port < 1 || port > 65535 || timeoutMs < 10 || timeoutMs > 200) {
                throw new IllegalArgumentException("Invalid order Token Server connection settings");
            }
            System.getProperties().putIfAbsent(SentinelConfig.APP_NAME_PROP_KEY, OrderTokenServer.CLIENT_NAMESPACE);
            // A replica can receive its first create request before any Sentinel SphU entry initializes the codecs.
            InitExecutor.doInit();
            ClusterClientConfigManager.applyNewConfig(new ClusterClientConfig().setRequestTimeout(timeoutMs));
            ClusterClientConfigManager.applyNewAssignConfig(new ClusterClientAssignConfig(host, port));
            return new DefaultClusterTokenClient();
        }

        @Bean
        ClusterOrderAdmission clusterOrderAdmission(DefaultClusterTokenClient orderTokenClient) {
            return new ClusterOrderAdmission(orderTokenClient);
        }
    }
}
