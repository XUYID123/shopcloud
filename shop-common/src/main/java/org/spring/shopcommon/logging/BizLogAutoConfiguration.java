package org.spring.shopcommon.logging;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

/** common SPI 自动注册同步业务日志；WebFlux 网关不安装此切面。 */
@AutoConfiguration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix="shop.logging.biz",name="enabled",havingValue="true",matchIfMissing=true)
public class BizLogAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    BizLogAspect bizLogAspect() {return new BizLogAspect();}
}
