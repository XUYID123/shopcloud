package org.spring.shopcommon.idempotent;

import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 存储故障必须 fail closed；null 不等于占位成功，也不等于重复请求。 */
class RedisIdempotentStoreTests {
    @Test void forwardsAtomicSetNxWithTtlAndMapsOnlyRedisFailures() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var ttl = Duration.ofSeconds(5);
        when(values.setIfAbsent("test-key", "1", ttl)).thenReturn(true, false, null)
                .thenThrow(new RedisConnectionFailureException("must-not-leak-credentials"));
        var store = new RedisIdempotentStore(redis);
        assertThat(store.acquire("test-key", ttl)).isTrue();
        assertThat(store.acquire("test-key", ttl)).isFalse();
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> store.acquire("test-key", ttl)).isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.getCode()).isEqualTo(503);
                assertThat(error.getBusinessCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                assertThat(error.getMessage()).doesNotContain("credentials");
            });
        }
        verify(values, times(4)).setIfAbsent("test-key", "1", ttl);
        verifyNoMoreInteractions(values);
    }
}
