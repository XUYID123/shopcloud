package org.spring.shopuser.auth;

import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Redis 故障必须是 503，不可伪装成令牌过期，更不可忽略保存失败返回登录成功。 */
class RefreshTokenStoreTests {
    @Test @SuppressWarnings("unchecked") void unavailableRedisDoesNotBecomeInvalidCredentials() {
        var resources = mock(AuthRedisConfiguration.Resources.class);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(resources.template()).thenReturn(redis); when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("private password must not leak"));
        var store = new RefreshTokenStore(resources, JwtUtilsTests.properties());
        String token = RefreshTokenStore.newToken();
        for (Runnable action : new Runnable[]{() -> store.save(42, token), () -> store.resolve(token), () -> store.revoke(token)}) {
            assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> {
                assertThat(error.getCode()).isEqualTo(503);
                assertThat(error.getBusinessCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                assertThat(error.getMessage()).doesNotContain("private password");
            });
        }
    }
}
