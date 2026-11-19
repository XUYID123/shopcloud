package org.spring.shoporder;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class ShopOrderApplicationTests {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.context.ApplicationContext context;

    @Test
    void contextLoads() {
        org.assertj.core.api.Assertions.assertThat(context.containsBean("internalAuthenticationFilterRegistration")).isTrue();
    }

}
