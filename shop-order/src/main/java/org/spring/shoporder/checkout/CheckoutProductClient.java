package org.spring.shoporder.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.codec.ErrorDecoder;
import org.spring.shopcommon.checkout.ProductQuote;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopcommon.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "shop-product", contextId = "checkoutProduct", url = "${shop.order.checkout.product-url:}",
        configuration = {CheckoutClientSettings.class, CheckoutProductClient.Security.class})
interface CheckoutProductClient {
    @PostMapping(path = "/internal/v1/products/quotes", consumes = "application/json")
    Result<ProductQuote> quote(@RequestBody PurchaseItems input);

    class Security {
        @Bean
        RequestInterceptor checkoutProductCredential(
                @Value("${shop.order.checkout.product-key-location}") Resource key) throws Exception {
            var scope = new ScopedServiceJwt.Scope("shop-order", "shop-product", "order:product:quote");
            return CheckoutClientSettings.credential(key, scope, false);
        }

        @Bean
        ErrorDecoder checkoutProductErrors(ObjectMapper json) {
            return CheckoutClientSettings.errors(json, ErrorCode.PRODUCT_UNAVAILABLE, ErrorCode.WEIGHT_LIMIT_EXCEEDED);
        }
    }
}
