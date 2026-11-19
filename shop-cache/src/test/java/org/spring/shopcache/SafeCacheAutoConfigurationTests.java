package org.spring.shopcache;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

/** 默认关闭，不建立网络连接，不意外侵入其他服务。 */
class SafeCacheAutoConfigurationTests {
    @Test void addingDependencyAloneDoesNotStartCacheInfrastructure() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(SafeCacheAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(SafeCacheTemplate.class);
                    assertThat(context).doesNotHaveBean("safeCacheRedisson").doesNotHaveBean("safeCacheRedisWorkers");
                });
    }
}
