package org.spring.shopcommon.idempotent;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

/**
 * 方法入口防重：解析 Key → Redis 原子占位 → 只让占位成功者执行原方法。
 * 不捕获原方法异常，不在 finally 删除 Key，避免误判业务故障或放开窗口内的顺序重复请求。
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class IdempotentAspect {
    private final RedisIdempotentStore store;
    private final IdempotentKeyResolver keys;

    public IdempotentAspect(RedisIdempotentStore store, IdempotentKeyResolver keys) {
        this.store = store;
        this.keys = keys;
    }

    /** 高优先级让默认事务拦截器处于内部，正常同步调用包含事务提交时间。 */
    @Around("@annotation(org.spring.shopcommon.idempotent.Idempotent)")
    public Object intercept(ProceedingJoinPoint joinPoint) throws Throwable {
        Method declared = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Method method = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(declared, joinPoint.getTarget().getClass()));
        Idempotent annotation = AnnotatedElementUtils.findMergedAnnotation(method, Idempotent.class);
        if (annotation == null) annotation = AnnotatedElementUtils.findMergedAnnotation(declared, Idempotent.class);
        if (annotation == null) throw new IllegalStateException("Missing @Idempotent method annotation");
        assertSynchronous(method);
        String key = keys.resolve(joinPoint.getTarget(), method, joinPoint.getArgs(), annotation);
        if (!store.acquire(key, Duration.ofSeconds(annotation.expireTime()))) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST, annotation.message());
        }
        // 标记依赖 TTL 自然过期：即使方法抛异常，可能已发生不可回滚的外部副作用，也不盲目放行重试。
        return joinPoint.proceed();
    }

    /** 同步模板不假装支持 Future/响应式方法；返回异步句柄并不代表业务已结束。 */
    private static void assertSynchronous(Method method) {
        Class<?> type = method.getReturnType();
        boolean publisher = ClassUtils.getAllInterfacesForClassAsSet(type).stream()
                .anyMatch(contract -> contract.getName().equals("org.reactivestreams.Publisher"));
        if (Future.class.isAssignableFrom(type) || CompletionStage.class.isAssignableFrom(type) || publisher
                || type.getName().equals("org.reactivestreams.Publisher")
                || AnnotatedElementUtils.hasAnnotation(method, org.springframework.scheduling.annotation.Async.class)
                || AnnotatedElementUtils.hasAnnotation(method.getDeclaringClass(), org.springframework.scheduling.annotation.Async.class)) {
            throw new IllegalStateException("@Idempotent supports synchronous methods only");
        }
    }
}
