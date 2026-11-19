package org.spring.shopproduct;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopproduct.cache.ProductCacheKeys;
import org.spring.shopproduct.cache.ProductDetailCache;
import org.spring.shopproduct.entity.ProductEntity;
import org.spring.shopproduct.mapper.ProductMapper;
import org.spring.shopproduct.mapper.SkuMapper;
import org.spring.shopproduct.service.ProductQueryService;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ProductDetailCacheTests {
    private final String key = ProductCacheKeys.detail(81);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ProductMapper products = mock(ProductMapper.class);
    private final SkuMapper skus = mock(SkuMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ProductQueryService service = new ProductQueryService(products, skus, transactions,
            new ProductDetailCache(redis, new ObjectMapper(), meters));

    @BeforeEach void captureCacheWrites() {
        AtomicReference<String> storedValue = new AtomicReference<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(key)).thenAnswer(call -> storedValue.get());
        doAnswer(call -> {
            storedValue.set(call.getArgument(1));
            return null;
        }).when(values).set(eq(key), anyString(), any(Duration.class));
    }

    @Test void valueHitSkipsDatabaseAndUsesJitteredTtl() {
        visibleProduct();
        var first = service.detail("81");
        assertThat(service.detail("81")).isEqualTo(first);
        verify(products, times(1)).selectVisibleById(81);
        verify(transactions, times(1)).getTransaction(any());
        verify(values).set(eq(key), contains("\"productId\":\"81\""),
                argThat((Duration ttl) -> ttl.toSeconds() >= 3_600 && ttl.toSeconds() <= 4_200));
        assertThat(count("miss")).isEqualTo(1);
        assertThat(count("hit")).isEqualTo(1);
    }

    @Test void nullHitKeeps404AndSkipsDatabaseWithThreeMinuteTtl() {
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.detail("81")).isInstanceOfSatisfying(BusinessException.class,
                    error -> {
                        assertThat(error.getCode()).isEqualTo(404);
                        assertThat(error.getBusinessCode()).isEqualTo("PRODUCT_NOT_FOUND");
                    });
        }
        verify(products, times(1)).selectVisibleById(81);
        verifyNoInteractions(skus);
        verify(values).set(key, "__NULL__", Duration.ofMinutes(3));
        assertThat(count("miss")).isEqualTo(1);
        assertThat(count("null_hit")).isEqualTo(1);
    }

    @Test void redisGetAndSetFailuresFallBackToDatabaseAndCountOnce(CapturedOutput output) {
        visibleProduct();
        when(values.get(key)).thenThrow(new RedisConnectionFailureException("sensitive address")).thenReturn(null);
        doThrow(new RedisConnectionFailureException("sensitive address")).when(values).set(eq(key), anyString(), any(Duration.class));
        assertThat(service.detail("81").productId()).isEqualTo("81");
        assertThat(service.detail("81").productId()).isEqualTo("81");
        verify(products, times(2)).selectVisibleById(81);
        verify(values, times(1)).set(eq(key), anyString(), any(Duration.class));
        assertThat(count("fallback")).isEqualTo(2);
        assertThat(count("miss")).isZero();
        assertThat(output.getAll()).contains("event=PRODUCT_CACHE_FALLBACK", "operation=get", "operation=set")
                .doesNotContain("sensitive address");
    }

    @Test void malformedJsonIsRebuiltAndInvalidIdNeverConsultsRedis() {
        assertThatThrownBy(() -> service.detail("9223372036854775808")).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(400));
        verifyNoInteractions(redis, products);
        visibleProduct();
        values.set(key, "{not valid", Duration.ofMinutes(60));
        assertThat(service.detail("81").productId()).isEqualTo("81");
        assertThat(service.detail("81").productId()).isEqualTo("81");
        verify(products, times(1)).selectVisibleById(81);
        assertThat(count("fallback")).isEqualTo(1);
        assertThat(count("hit")).isEqualTo(1);
    }

    private void visibleProduct() {
        var product = new ProductEntity();
        product.setId(81L);
        product.setCategoryId(3L);
        product.setName("面粉");
        product.setStatus("ON_SALE");
        product.setVersion(1L);
        product.setShelfLifeDays(90);
        when(products.selectVisibleById(81)).thenReturn(product);
        when(skus.selectEnabledByProduct(81)).thenReturn(List.of());
    }

    private double count(String result) {
        return meters.get("product_cache_requests_total").tag("result", result).counter().count();
    }
}
