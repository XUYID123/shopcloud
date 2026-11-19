package org.spring.shopcache;

import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.*;
import io.github.resilience4j.timelimiter.*;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.dao.TransientDataAccessResourceException;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** 不依赖外部网络的保护层测试，重点验证真实超时取消、重试边界、隔离和熔断行为。 */
class CacheDependencyExecutorTests {
    private final ThreadPoolExecutor redis = pool();
    private final ThreadPoolExecutor database = pool();
    private final RetryRegistry retries = RetryRegistry.ofDefaults();
    private final TimeLimiterRegistry limiters = TimeLimiterRegistry.ofDefaults();
    private final CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    private CacheDependencyExecutor executor;

    @BeforeEach void setup() { executor = new CacheDependencyExecutor(retries, breakers, limiters, redis, database); }
    @AfterEach void cleanup() { redis.shutdownNow(); database.shutdownNow(); MDC.clear(); Thread.interrupted(); }

    @Test void transientFailureRetriesButBusinessFailureDoesNot() {
        AtomicInteger calls = new AtomicInteger();
        String value = executor.execute(CacheDependencyExecutor.DATABASE, () -> {
            if (calls.incrementAndGet() == 1) throw new TransientDataAccessResourceException("temporary");
            return "ok";
        });
        assertThat(value).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
        calls.set(0);
        assertThatThrownBy(() -> executor.execute(CacheDependencyExecutor.DATABASE, () -> {
            calls.incrementAndGet(); throw new IllegalArgumentException("invalid input");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void timeoutInterruptsWorkerAndDoesNotRetryIt() throws Exception {
        limiters.replace(CacheDependencyExecutor.DATABASE, TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(100)).cancelRunningFuture(true).build()));
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(CacheDependencyExecutor.DATABASE, () -> {
            calls.incrementAndGet();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException error) { interrupted.countDown(); throw error; }
            return "late";
        })).isInstanceOf(CacheUnavailableException.class).hasCauseInstanceOf(TimeoutException.class);
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void openCircuitRejectsWithoutExecutingAndDoesNotAffectOtherDependency() {
        breakers.circuitBreaker(CacheDependencyExecutor.REDIS_READ).transitionToOpenState();
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> executor.execute(CacheDependencyExecutor.REDIS_READ, () -> calls.incrementAndGet()))
                .isInstanceOf(CacheUnavailableException.class).hasCauseInstanceOf(CallNotPermittedException.class);
        assertThat(calls.get()).isZero();
        assertThat(executor.execute(CacheDependencyExecutor.DATABASE, () -> "db-ok")).isEqualTo("db-ok");
    }

    @Test void failuresActuallyOpenCircuitAndHalfOpenSuccessRecovers() {
        breakers.replace(CacheDependencyExecutor.DATABASE, CircuitBreaker.of("test-db", CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50)
                .permittedNumberOfCallsInHalfOpenState(1).build()));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> executor.execute(CacheDependencyExecutor.DATABASE, () -> {
                throw new java.sql.SQLException("database offline");
            })).isInstanceOf(CacheUnavailableException.class);
        }
        CircuitBreaker breaker = breakers.circuitBreaker(CacheDependencyExecutor.DATABASE);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        breaker.transitionToHalfOpenState();
        assertThat(executor.execute(CacheDependencyExecutor.DATABASE, () -> "recovered")).isEqualTo("recovered");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test void fullDatabasePoolRejectsImmediatelyWithoutRunningOnCaller() throws Exception {
        CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
        database.submit(() -> { occupied.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        assertThat(occupied.await(1, TimeUnit.SECONDS)).isTrue();
        database.submit(() -> "queued");
        AtomicInteger calls = new AtomicInteger();
        try {
            assertThatThrownBy(() -> executor.execute(CacheDependencyExecutor.DATABASE, () -> calls.incrementAndGet()))
                    .isInstanceOf(CacheUnavailableException.class).hasCauseInstanceOf(RejectedExecutionException.class);
            assertThat(calls.get()).isZero();
            assertThat(executor.execute(CacheDependencyExecutor.REDIS_READ, () -> "redis-still-available")).isEqualTo("redis-still-available");
        } finally { release.countDown(); }
    }

    @Test void workerReceivesTraceIdAndRestoresItsPreviousMdc() throws Exception {
        MDC.put("traceId", "cache-test-id");
        assertThat(executor.execute(CacheDependencyExecutor.DATABASE, () -> MDC.get("traceId"))).isEqualTo("cache-test-id");
        assertThat(database.submit(() -> MDC.get("traceId")).get(1, TimeUnit.SECONDS)).isNull();
    }

    @Test void interruptedCallerPreservesInterruptAndCancelsQueuedTask() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> executor.execute(CacheDependencyExecutor.DATABASE, () -> "unused"))
                    .isInstanceOf(CacheUnavailableException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    private static ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
