package org.spring.shopcommon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.spring.shopcommon.idempotent.IdempotentAspect;
import org.spring.shopcommon.trace.MdcTaskDecorator;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ShopCommonApplicationTests {
	@Autowired private ApplicationContext context;

	@Test
	void contextLoads() {
		// 通过真正的 Boot imports 发现，不在测试中手动 @Import 幂等/Trace 配置。
		assertThat(context.getBeansOfType(IdempotentAspect.class)).hasSize(1);
		assertThat(context.getBeansOfType(MdcTaskDecorator.class)).hasSize(1);
	}

}
