package org.spring.shopcommon.idempotent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 可选覆盖项：默认复用安全 Redis 的环境变量与本项目开发默认值，不依赖宿主的默认 RedisTemplate。
 * 配置属于基础设施；绝不能由前端请求修改连接位置、凭据或命名规则。
 */
@ConfigurationProperties("shop.idempotent")
public class IdempotentProperties {
    private final Redis redis = new Redis();
    public Redis getRedis() { return redis; }

    public static class Redis {
        private String host;
        private Integer port;
        private String username;
        private String password;
        private int database;
        private boolean ssl;
        private Duration timeout = Duration.ofSeconds(1);

        // host/port/password 为 null 时，自动配置才读取 SHOP_GATEWAY_REDIS_* 环境变量。
        public String getHost() { return host; }
        public void setHost(String value) { host = value; }
        public Integer getPort() { return port; }
        public void setPort(Integer value) { port = value; }
        public String getUsername() { return username; }
        public void setUsername(String value) { username = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { password = value; }
        public int getDatabase() { return database; }
        public void setDatabase(int value) { database = value; }
        public boolean isSsl() { return ssl; }
        public void setSsl(boolean value) { ssl = value; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration value) { timeout = value; }
    }
}
