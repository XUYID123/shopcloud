package org.spring.shoporder.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.codec.Decoder;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopcommon.result.Result;
import org.spring.shoporder.client.OrderFeignSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@FeignClient(name = "shop-inventory", contextId = "fulfillmentInventory", url = "${shop.order.m5.inventory-url:}",
        configuration = FulfillmentInventoryClient.Security.class)
interface FulfillmentInventoryClient {
    @GetMapping("/internal/v1/inventory/reservations/{orderNo}/allocation")
    Result<Allocation> allocation(@PathVariable("orderNo") String orderNo);

    record Allocation(String orderNo, String reservationState, Boolean shippable,
                      List<OrderView.BatchAllocation> allocations) { }

    class Security extends OrderFeignSettings {
        @Bean
        RequestInterceptor inventoryCredential(@Value("${shop.order.m5.inventory-key-location}") Resource resource) throws Exception {
            var scope = new ScopedServiceJwt.Scope("shop-order", "shop-inventory", "order:inventory:read");
            return credential(resource, scope, false);
        }

        @Bean
        Decoder inventoryAllocationDecoder(ObjectMapper json) {
            return boundedDecoder(json, 1024 * 1024);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = {"shop.order.m3.enabled", "shop.order.m4.enabled", "shop.order.m5.enabled"}, havingValue = "true")
    @EnableFeignClients(clients = FulfillmentInventoryClient.class)
    class Module { }
}
