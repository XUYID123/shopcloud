package org.spring.shoporder.checkout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = CheckoutModuleConfiguration.ENABLED, havingValue = "true")
@EnableFeignClients(clients = {CheckoutAddressClient.class, CheckoutProductClient.class})
class CheckoutModuleConfiguration {
    static final String ENABLED = "shop.order.checkout.enabled";
}
