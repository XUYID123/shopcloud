package org.spring.shoporder.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.Decoder;
import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopcommon.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;

@FeignClient(name = "shop-product", contextId = "orderCartProduct", url = "${shop.order.cart.product-url:}",
        configuration = CartProductClient.Security.class)
public interface CartProductClient {
    @GetMapping("/internal/v1/products/skus/cart-metadata")
    Result<List<Sku>> find(@RequestParam("skuId") List<Long> skuIds);

    record Sku(String skuId, String productName, String specName, Long priceFen, Integer weightGram) { }

    /** The private key is used only for this client and this permission. */
    class Security {
        @Bean
        RequestInterceptor cartProductCredential(
                @Value("${shop.order.cart.signing-private-key-location}") Resource resource) throws Exception {
            var key = ScopedServiceJwt.privateKey(resource);
            var scope = new ScopedServiceJwt.Scope("shop-order", "shop-product", "product:cart-metadata:read");
            var clock = Clock.systemUTC();
            return request -> {
                String url = request.url();
                int queryStart = url.indexOf('?');
                String query = queryStart < 0 ? null : url.substring(queryStart + 1);
                request.header(InternalJwtProtocol.HEADER,
                        ScopedServiceJwt.signWithQuery(key, scope, request.method(), request.path(), query, clock));
            };
        }

        @Bean
        Retryer cartProductRetryer() { return Retryer.NEVER_RETRY; }

        @Bean
        Request.Options cartProductOptions() {
            return new Request.Options(500, TimeUnit.MILLISECONDS, 1000, TimeUnit.MILLISECONDS, false);
        }

        @Bean
        Logger.Level cartProductLogLevel() { return Logger.Level.NONE; }

        @Bean
        Decoder cartProductDecoder(ObjectMapper json) {
            return (response, type) -> {
                if (response.body() == null) throw new IOException("Missing cart metadata response");
                int maxBytes = 32 * 1024;
                try (var input = response.body().asInputStream()) {
                    byte[] bytes = input.readNBytes(maxBytes + 1);
                    if (bytes.length > maxBytes) throw new IOException("Cart metadata response too large");
                    return json.readValue(bytes, json.constructType(type));
                }
            };
        }
    }
}
