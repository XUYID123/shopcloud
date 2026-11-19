package org.spring.shopcache;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 统一依赖保护：Retry( CircuitBreaker( TimeLimiter(有界执行器任务) ) )。
 * Redis 读、Redis 写、数据库读使用独立保护实例；数据库与 Redis 不共用工作线程。
 * 本类是同步 API，只供 MVC/后台任务调用，不能直接运行在 WebFlux/Netty I/O 线程。
 */
public class CacheDependencyExecutor {
    public static final String REDIS_READ = "safeCacheRedisRead"; // 读故障不会熔断数据库。
    public static final String REDIS_WRITE = "safeCacheRedisWrite"; // 写失败不影响已有热缓存读取。
    public static final String DATABASE = "safeCacheDatabase"; // 冷加载保护，不依赖具体 ORM。
    private static final Logger log = LoggerFactory.getLogger(CacheDependencyExecutor.class);
    private final RetryRegistry retries;
    private final CircuitBreakerRegistry breakers;
    private final TimeLimiterRegistry timeLimiters;
    private final ThreadPoolExecutor redisWorkers;
    private final ThreadPoolExecutor databaseWorkers;

    /** Registry 来自 Resilience4j Boot 自动配置，所有阈值从应用 application.yml 读取。 */
    public CacheDependencyExecutor(RetryRegistry retries, CircuitBreakerRegistry breakers, TimeLimiterRegistry timeLimiters,
                                   ThreadPoolExecutor redisWorkers, ThreadPoolExecutor databaseWorkers) {
        this.retries = retries;
        this.breakers = breakers;
        this.timeLimiters = timeLimiters;
        this.redisWorkers = redisWorkers;
        this.databaseWorkers = databaseWorkers;
        // 每个实例只注册一次事件日志；不输出原始 Key、缓存内容、凭据或异常 message。
        for (String name : new String[]{REDIS_READ, REDIS_WRITE, DATABASE}) {
            // 未提供 YAML 时也使用保守默认值；已由 YAML 创建的同名实例优先，不覆盖应用配置。
            timeLimiters.timeLimiter(name, () -> TimeLimiterConfig.custom()
                    .timeoutDuration(DATABASE.equals(name) ? Duration.ofMillis(1500) : Duration.ofMillis(250))
                    .cancelRunningFuture(true).build());
            breakers.circuitBreaker(name, () -> CircuitBreakerConfig.custom().slidingWindowSize(20)
                    .minimumNumberOfCalls(10).failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(10))
                    .ignoreExceptions(IllegalArgumentException.class, InterruptedException.class, RejectedExecutionException.class)
                    .permittedNumberOfCallsInHalfOpenState(2).build()).getEventPublisher().onStateTransition(event ->
                    log.warn("SafeCache circuit transition; dependency={}, transition={}", name, event.getStateTransition()));
            retries.retry(name, () -> RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ofMillis(25))
                    // 超时后不立即再次占用数据库连接；业务校验、熔断拒绝、满队列也不重试。
                    .retryOnException(error -> !(error instanceof QueryTimeoutException) && !(error instanceof SQLTimeoutException)
                            && (error instanceof TransientDataAccessException || error instanceof RedisConnectionFailureException
                            || error instanceof SQLTransientException))
                    .build()).getEventPublisher().onRetry(event ->
                    log.warn("SafeCache retry; dependency={}, attempt={}, type={}", name,
                            event.getNumberOfRetryAttempts(), event.getLastThrowable().getClass().getSimpleName()));
        }
    }

    /** 执行单个依赖操作。调用者必须只传入查询或幂等 SET/DEL；不允许在此重试业务写事务。 */
    public <T> T execute(String name, Callable<T> action) {
        ThreadPoolExecutor pool = DATABASE.equals(name) ? databaseWorkers : redisWorkers;
        // 捕获关联信息并在工作线程恢复；不会传播 Spring 的线程绑定事务或 JDBC Connection。
        Map<String, String> context = MDC.getCopyOfContextMap();
        Callable<T> limited = () -> {
            Future<T> future = pool.submit(() -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (context == null) MDC.clear(); else MDC.setContextMap(context);
                    return action.call();
                } finally {
                    if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
                }
            });
            try {
                // 使用真实 Future，超时可 cancel(true)；不能直接给同步 Supplier 加注解假装超时。
                return timeLimiters.timeLimiter(name).executeFutureSupplier(() -> future);
            } catch (InterruptedException error) {
                // 调用线程取消等待时也取消尚未完成的任务，不让排队任务在请求退出后才开始查询。
                future.cancel(true);
                throw error;
            } finally {
                // 取消排队中的任务后及时移出有界队列；已运行且忽略中断的 I/O 仍依靠底层超时收敛。
                if (future.isCancelled()) pool.remove((Runnable) future);
            }
        };
        try {
            return Retry.decorateCallable(retries.retry(name),
                    CircuitBreaker.decorateCallable(breakers.circuitBreaker(name), limited)).call();
        } catch (InterruptedException error) {
            // 保留线程中断状态，调用方 finally 仍会释放自己持有的锁。
            Thread.currentThread().interrupt();
            throw new CacheUnavailableException("Cache operation interrupted", error);
        } catch (Exception error) {
            // 只将依赖故障转为降级信号，业务校验/程序错误保持原始异常语义。
            if (error instanceof DataAccessException || error instanceof SQLException || error instanceof IOException
                    || error instanceof TimeoutException || error instanceof CallNotPermittedException
                    || error instanceof RejectedExecutionException) {
                throw new CacheUnavailableException("Cache dependency unavailable: " + name, error);
            }
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Unexpected checked exception from cache loader", error);
        }
    }
}
