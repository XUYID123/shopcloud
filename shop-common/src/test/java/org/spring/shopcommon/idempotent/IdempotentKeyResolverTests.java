package org.spring.shopcommon.idempotent;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.*;

/** 校验命名参数/索引别名、服务/方法隔离和非法配置，避免错误 Key 演变成全局误拦截。 */
class IdempotentKeyResolverTests {
    private final Sample target = new Sample();
    private final IdempotentKeyResolver resolver = new IdempotentKeyResolver("shop-order");

    @Test void resolvesDtoFieldsAndAllArgumentAliasesWithoutExposingRawValues() throws Exception {
        var order = new Order("sensitive-order-123");
        for (String method : new String[]{"named", "indexed", "alias", "array"}) {
            String key = resolve(resolver, method, order);
            assertThat(key).startsWith("idempotent:").hasSize("idempotent:".length() + 64)
                    .doesNotContain(order.orderId());
            assertThat(resolve(resolver, method, order)).isEqualTo(key);
            assertThat(resolve(resolver, method, new Order("another-order"))).isNotEqualTo(key);
        }
    }

    @Test void methodAndApplicationArePartOfKeyScope() throws Exception {
        var dto = new Order("42");
        assertThat(resolve(resolver, "named", dto)).isNotEqualTo(resolve(resolver, "indexed", dto));
        assertThat(resolve(resolver, "named", dto)).isNotEqualTo(resolve(new IdempotentKeyResolver("shop-pay"), "named", dto));
    }

    @Test void invalidNullBlankObjectExpressionOrTtlNeverProducesAKey() {
        for (String method : new String[]{"nullKey", "blankKey", "objectKey", "badExpression", "badTtl"}) {
            assertThatThrownBy(() -> resolve(resolver, method, new Order("private-order")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid @Idempotent")
                    .hasMessageNotContaining("private-order");
        }
    }

    private String resolve(IdempotentKeyResolver keys, String name, Order dto) throws Exception {
        Method method = Sample.class.getMethod(name, Order.class);
        return keys.resolve(target, method, new Object[]{dto}, method.getAnnotation(Idempotent.class));
    }
    public record Order(String orderId) { }
    public static class Sample {
        @Idempotent(key = "#dto.orderId") public void named(Order dto) { }
        @Idempotent(key = "#p0.orderId") public void indexed(Order dto) { }
        @Idempotent(key = "#a0.orderId") public void alias(Order dto) { }
        @Idempotent(key = "#args[0].orderId") public void array(Order dto) { }
        @Idempotent(key = "null") public void nullKey(Order dto) { }
        @Idempotent(key = "' '") public void blankKey(Order dto) { }
        @Idempotent(key = "#dto") public void objectKey(Order dto) { }
        @Idempotent(key = "#dto.missing") public void badExpression(Order dto) { }
        @Idempotent(key = "#dto.orderId", expireTime = 0) public void badTtl(Order dto) { }
    }
}
