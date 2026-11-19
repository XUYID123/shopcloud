package org.spring.shopcommon.idempotent;

import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 AOP 代理：不能只直接调用切面方法而漏掉注解切点/方法解析问题。 */
class IdempotentAspectTests {
    @Test void onlyAcquiredCallReachesBusinessAndDuplicateKeepsCustom409Message() {
        var store = mock(RedisIdempotentStore.class);
        when(store.acquire(anyString(), eq(Duration.ofSeconds(5)))).thenReturn(true, false);
        var target = new Sample();
        Sample proxy = proxy(target, store);
        assertThat(proxy.create("42")).isEqualTo("42");
        assertThatThrownBy(() -> proxy.create("42")).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.getCode()).isEqualTo(409);
            assertThat(error.getBusinessCode()).isEqualTo("DUPLICATE_REQUEST");
            assertThat(error.getMessage()).isEqualTo("请勿重复创建订单");
        });
        assertThat(target.calls.get()).isEqualTo(1);
        verify(store, times(2)).acquire(anyString(), eq(Duration.ofSeconds(5)));
    }

    @Test void originalBusinessFailureIsNotReclassifiedAsRedisFailure() {
        var store = mock(RedisIdempotentStore.class);
        when(store.acquire(anyString(), any())).thenReturn(true);
        assertThatThrownBy(() -> proxy(new Sample(), store).fail("42"))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getBusinessCode()).isEqualTo("STATE_CONFLICT"));
    }

    @Test void asynchronousReturnTypeIsRejectedBeforeRedisOrBusiness() {
        var store = mock(RedisIdempotentStore.class);
        assertThatThrownBy(() -> proxy(new Sample(), store).async("42")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }

    @Test void jdkProxyFindsAnnotationAndParameterOnImplementation() {
        var store = mock(RedisIdempotentStore.class);
        when(store.acquire(anyString(), any())).thenReturn(true);
        var factory = new AspectJProxyFactory(new ContractImpl());
        factory.setInterfaces(Contract.class);
        factory.setProxyTargetClass(false);
        factory.addAspect(new IdempotentAspect(store, new IdempotentKeyResolver("test-service")));
        Contract proxy = factory.getProxy();
        assertThat(proxy.execute("42")).isEqualTo("42");
        verify(store).acquire(anyString(), eq(Duration.ofSeconds(5)));
    }

    private Sample proxy(Sample target, RedisIdempotentStore store) {
        var factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new IdempotentAspect(store, new IdempotentKeyResolver("test-service")));
        return factory.getProxy();
    }
    public static class Sample {
        final AtomicInteger calls = new AtomicInteger();
        @Idempotent(key = "#id", message = "请勿重复创建订单")
        public String create(String id) { calls.incrementAndGet(); return id; }
        @Idempotent(key = "#id")
        public void fail(String id) { throw new BusinessException(ErrorCode.STATE_CONFLICT); }
        @Idempotent(key = "#id")
        public CompletableFuture<String> async(String id) { return CompletableFuture.completedFuture(id); }
    }
    public interface Contract { String execute(String id); }
    public static class ContractImpl implements Contract {
        @Override @Idempotent(key = "#id") public String execute(String id) { return id; }
    }
}
