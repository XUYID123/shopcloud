package org.spring.shopcommon.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** 验证提交时快照、线程复用不串号、异常恢复及同线程嵌套不破坏父上下文。 */
class MdcTaskDecoratorTests {
    @AfterEach void clear() { MDC.clear(); }

    @Test void snapshotAndSameThreadExceptionRestoreOriginalContext() {
        var decorator = new MdcTaskDecorator();
        MDC.put("traceId", "submitted");
        MDC.put("business", "parent-value");
        Runnable decorated = decorator.decorate(() -> {
            assertThat(MDC.get("traceId")).isEqualTo("submitted");
            assertThat(MDC.get("business")).isEqualTo("parent-value");
            MDC.put("child", "discard");
            throw new IllegalStateException("child failure");
        });
        MDC.clear(); MDC.put("traceId", "execution-before");
        assertThatThrownBy(decorated::run).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.getCopyOfContextMap()).containsOnly(entry("traceId", "execution-before"));
    }

    @Test void completableFutureUsesDecoratedPoolAndNextTaskHasNoTrace() throws Exception {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1);
        executor.setTaskDecorator(new MdcTaskDecorator()); executor.initialize();
        try {
            MDC.put("traceId", "async-request");
            var first = CompletableFuture.supplyAsync(() -> MDC.get("traceId"), executor);
            MDC.clear();
            assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo("async-request");
            assertThat(CompletableFuture.supplyAsync(() -> MDC.get("traceId"), executor).get(3, TimeUnit.SECONDS)).isNull();
            // 直接检查同一底层线程，避免只验证装饰器再次安装空上下文而掩盖残留。
            assertThat(executor.getThreadPoolExecutor().submit(MDC::getCopyOfContextMap).get(3, TimeUnit.SECONDS)).isNullOrEmpty();
        } finally { executor.shutdown(); }
    }
}
