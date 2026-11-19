package org.spring.shopcommon.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 有界业务异步线程池；参数按服务自己的负载调整，不替代网关专用 Feign/验签 Scheduler。 */
@ConfigurationProperties("shop.trace.async")
public class TraceAsyncProperties {
    private int corePoolSize = 4;
    private int maxPoolSize = 16;
    private int queueCapacity = 256;
    private int awaitTerminationSeconds = 20;

    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int value) { corePoolSize = value; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public void setMaxPoolSize(int value) { maxPoolSize = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public int getAwaitTerminationSeconds() { return awaitTerminationSeconds; }
    public void setAwaitTerminationSeconds(int value) { awaitTerminationSeconds = value; }
}
