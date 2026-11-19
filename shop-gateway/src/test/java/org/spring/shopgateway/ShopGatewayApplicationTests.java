package org.spring.shopgateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.spring.shopcommon.idempotent.IdempotentAspect;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ShopGatewayApplicationTests {
    @Autowired private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context.containsBean("internalAuthenticationFilterRegistration")).isFalse();
        // common 引入同步 Redis 组件后，WebFlux 网关仍不装配其阻塞式幂等切面。
        assertThat(context.getBeansOfType(IdempotentAspect.class)).isEmpty();
    }

}
