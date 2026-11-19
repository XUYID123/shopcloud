package org.spring.shopgateway.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLockReactive;
import org.redisson.api.RedissonReactiveClient;
import org.spring.shopcommon.auth.UserPermissionSnapshot;
import org.spring.shopcommon.result.Result;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.server.PathContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RbacPermissionCacheTests {
    private final jakarta.validation.ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final RbacCodec codec = new RbacCodec(new ObjectMapper(), validation.getValidator());
    private final ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    @SuppressWarnings("unchecked") private final ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
    private final UserPermissionClient client = mock(UserPermissionClient.class);
    private final RedissonReactiveClient redisson = mock(RedissonReactiveClient.class);
    private final RLockReactive lock = mock(RLockReactive.class);
    private final reactor.core.scheduler.Scheduler workers = Schedulers.newBoundedElastic(2, 10, "test-permission-feign");
    private final RbacPermissionCache cache = new RbacPermissionCache(redis, JwtIdentityVerifierTests.properties(), codec, client, workers, redisson);

    @AfterEach void close() { workers.dispose(); validation.close(); }

    @Test void warmReadsStillConsultRedisForEveryRequest() {
        initialize();
        when(values.get(cache.userKey("42"))).thenReturn(Mono.just(codec.write(
                new RbacModels.UserGrant("42", "v1", true, Set.of("product:write")))));
        assertThat(authorize().block()).isTrue();
        assertThat(authorize().block()).isTrue();
        verify(values, times(2)).get(cache.userKey("42"));
        verify(values, times(2)).get(cache.rulesKey());
        verifyNoInteractions(client);
    }

    @Test void ruleMissAndRedisFailureNeverTriggerDatabaseFallback() {
        initialize();
        assertThat(cache.authorize("42", "POST", PathContainer.parsePath("/unmapped"), "products", "test").block()).isFalse();
        when(values.get(cache.rulesKey())).thenReturn(Mono.error(new IllegalStateException("Redis offline")));
        assertThatThrownBy(() -> authorize().block()).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(client);
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void blockingFeignCallRunsOnDedicatedWorkersEvenWhenSubscribedFromANonBlockingThread() {
        initialize();
        when(values.get(cache.userKey("42"))).thenReturn(Mono.empty());
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(redisson.getId()).thenReturn("test-redisson-client");
        when(lock.tryLock(eq(0L), anyLong(), eq(java.util.concurrent.TimeUnit.MILLISECONDS), anyLong())).thenReturn(Mono.just(true));
        when(lock.unlock(anyLong())).thenReturn(Mono.empty());
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenAnswer(call -> {
            RedisScript<?> script = call.getArgument(0);
            return script.getResultType() == String.class ? Flux.just("epoch-1") : Flux.just(1L);
        });
        AtomicReference<String> thread = new AtomicReference<>();
        when(client.permissions(eq("42"), anyString(), eq("test-request"))).thenAnswer(call -> {
            thread.set(Thread.currentThread().getName());
            return Result.success(new UserPermissionSnapshot("42", true, Set.of("product:write")));
        });
        assertThat(authorize().subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(3))).isTrue();
        assertThat(thread.get()).startsWith("test-permission-feign-");
    }

    private void initialize() {
        var signer = mock(InternalTokenSigner.class); when(signer.permissionQuery(anyString())).thenReturn("test-jwt"); cache.setInternalSigner(signer);
        when(redis.opsForValue()).thenReturn(values);
        var rules = new RbacModels.RuleSet("r1", List.of(new RbacModels.Rule("products",
                new RbacModels.Endpoint(Set.of("GET"), List.of("/products/**")), Set.of(), Set.of("product:write"), RbacModels.Match.ALL)));
        when(values.get(cache.rulesKey())).thenReturn(Mono.just(codec.write(rules)));
    }
    private Mono<Boolean> authorize() {
        return cache.authorize("42", "GET", PathContainer.parsePath("/products/1"), "products", "test-request");
    }
}
