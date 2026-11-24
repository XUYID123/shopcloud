package org.spring.shoporder.admission;

import com.alibaba.csp.sentinel.cluster.flow.rule.ClusterFlowRuleManager;
import com.alibaba.csp.sentinel.cluster.server.SentinelDefaultTokenServer;
import com.alibaba.csp.sentinel.cluster.server.config.ClusterServerConfigManager;
import com.alibaba.csp.sentinel.cluster.server.config.ServerTransportConfig;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.ClusterFlowConfig;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spring.shopcommon.protection.NacosQpsRule;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/** Standalone entry point. Runs on the private network shared with the order replicas. */
public final class OrderTokenServer {
    static final String CLIENT_NAMESPACE = "shop-order";
    static final long CREATE_FLOW_ID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(OrderTokenServer.class);

    private OrderTokenServer() { }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("SHOP_ORDER_TOKEN_SERVER_PORT", "18730"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid Sentinel Token Server port");
        ClusterServerConfigManager.loadServerNamespaceSet(Set.of(CLIENT_NAMESPACE));
        ClusterServerConfigManager.loadGlobalTransportConfig(new ServerTransportConfig(port, 120));
        var stop = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.countDown();
            try {
                stopped.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "order-token-shutdown"));
        try (var watcher = NacosQpsRule.open(env("NACOS_SERVER_ADDR", "localhost:8848"),
                env("NACOS_NAMESPACE", ""), env("NACOS_USERNAME", ""), env("NACOS_PASSWORD", ""),
                "shop-order-create.json", OrderTokenServer::applyRule)) {
            var server = new SentinelDefaultTokenServer();
            server.start();
            try {
                LOG.info("event=ORDER_TOKEN_SERVER_STARTED port={}", port);
                stop.await();
            } finally {
                server.stop();
            }
        } finally {
            stopped.countDown();
        }
    }

    private static void applyRule(int qps) {
        var rule = new FlowRule("order.create");
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qps);
        rule.setClusterMode(true);
        rule.setClusterConfig(new ClusterFlowConfig().setFlowId(CREATE_FLOW_ID).setThresholdType(1));
        ClusterFlowRuleManager.loadRules(CLIENT_NAMESPACE, List.of(rule));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
