package org.spring.shopcommon.protection;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/** Explicit opt-in source for a single bounded QPS rule. */
public final class NacosQpsRule implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(NacosQpsRule.class);
    private static final String GROUP = "SENTINEL_RULES";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ConfigService service;
    private final ScheduledExecutorService poller;
    private final String dataId;
    private final IntConsumer apply;
    private int lastApplied;
    private boolean degraded;

    public static NacosQpsRule open(String addresses, String namespace, String username, String password,
                                   String dataId, IntConsumer apply) throws NacosException {
        var properties = new Properties();
        properties.setProperty("serverAddr", addresses);
        properties.setProperty("namespace", namespace);
        if (!username.isBlank()) {
            properties.setProperty("username", username);
            properties.setProperty("password", password);
        }
        ConfigService service = NacosFactory.createConfigService(properties);
        try {
            return new NacosQpsRule(service, dataId, apply);
        } catch (IllegalStateException | NacosException failure) {
            try {
                service.shutDown();
            } catch (NacosException closeFailure) {
                LOG.warn("event=NACOS_RULE_CLOSE_FAILED dataId={}", dataId);
            }
            throw new IllegalStateException("Required Nacos rule is unavailable: " + dataId);
        }
    }

    private NacosQpsRule(ConfigService service, String dataId, IntConsumer apply) throws NacosException {
        this.service = service;
        this.dataId = dataId;
        this.apply = apply;
        load();
        poller = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "nacos-qps-" + dataId);
            thread.setDaemon(true);
            return thread;
        });
        poller.scheduleWithFixedDelay(this::refresh, 2, 2, TimeUnit.SECONDS);
    }

    private void refresh() {
        try {
            load();
            if (degraded) LOG.info("event=NACOS_RULE_RECOVERED dataId={}", dataId);
            degraded = false;
        } catch (IllegalStateException | NacosException failure) {
            if (!degraded) LOG.warn("event=NACOS_RULE_RETAINED dataId={} qps={}", dataId, lastApplied);
            degraded = true;
        }
    }

    private void load() throws NacosException {
        String document = service.getConfig(dataId, GROUP, 3000);
        if (!"UP".equals(service.getServerStatus())) throw new IllegalStateException("Nacos disconnected");
        int qps = parse(document);
        if (qps != lastApplied) {
            apply.accept(qps);
            lastApplied = qps;
            LOG.info("event=NACOS_RULE_APPLIED dataId={} qps={}", dataId, qps);
        }
    }

    static int parse(String document) {
        if (document != null && document.length() <= 128) {
            try {
                var rule = JSON.readTree(document);
                if (rule != null && rule.isObject() && rule.size() == 1) {
                    var qps = rule.path("qps");
                    if (qps.isIntegralNumber() && qps.canConvertToInt()) {
                        int value = qps.intValue();
                        if (value >= 1 && value <= 100_000) return value;
                    }
                }
            } catch (IOException invalid) {
                throw new IllegalStateException("Invalid Nacos QPS rule");
            }
        }
        throw new IllegalStateException("Missing or invalid Nacos QPS rule");
    }

    @Override
    public void close() throws NacosException {
        poller.shutdownNow();
        service.shutDown();
    }
}
