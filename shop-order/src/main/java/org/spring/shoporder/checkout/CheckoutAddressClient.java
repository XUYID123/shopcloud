package org.spring.shoporder.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopcommon.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "shop-user", contextId = "checkoutAddress", url = "${shop.order.checkout.user-url:}",
        configuration = {CheckoutClientSettings.class, CheckoutAddressClient.Security.class})
interface CheckoutAddressClient {
    @GetMapping("/internal/v1/users/addresses/{addressId}")
    Result<Address> find(@PathVariable("addressId") long addressId, @RequestParam("userId") long userId);

    record Address(String addressId, String receiver, String phone, String regionCode, String detail, Boolean isDefault) { }

    class Security {
        @Bean
        RequestInterceptor checkoutAddressCredential(
                @Value("${shop.order.checkout.address-key-location}") Resource key) throws Exception {
            var scope = new ScopedServiceJwt.Scope("shop-order", "shop-user", "order:address:read");
            return CheckoutClientSettings.credential(key, scope, true);
        }

        @Bean
        ErrorDecoder checkoutAddressErrors(ObjectMapper json) {
            return CheckoutClientSettings.errors(json, ErrorCode.NOT_FOUND);
        }
    }
}
