package org.spring.shopinventory.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopcommon.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Clock;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** 调用商品服务的权威数据，默认通过 Nacos 发现服务；共用 Feign Trace 拦截器。 */
@FeignClient(name = "shop-product", contextId = "inventoryStockProduct", url = "${shop.inventory.product-url:}",
        configuration = StockProductClient.Security.class)
public interface StockProductClient {
    @GetMapping("/internal/v1/products/skus/{skuId}/stock-metadata")
    Result<SkuMetadata> find(@PathVariable("skuId") long skuId);

    record SkuMetadata(Integer shelfLifeDays) { }

    /** 只对这个 Feign 客户端签发库存服务凭据。签名私钥仅加载到库存服务。 */
    class Security {
        @Bean
        RequestInterceptor stockProductCredential(
                @Value("${shop.inventory.signing-private-key-location}") Resource resource) throws Exception {
            var key = ScopedServiceJwt.privateKey(resource);
            var scope = new ScopedServiceJwt.Scope("shop-inventory", "shop-product", "product:stock-metadata:read");
            var clock = Clock.systemUTC();
            return request -> request.header(InternalJwtProtocol.HEADER,
                    ScopedServiceJwt.sign(key, scope, request.method(), request.path(), clock));
        }

        @Bean
        Retryer stockProductRetryer() { return Retryer.NEVER_RETRY; }

        /** 禁用重定向，避免携带服务凭据访问其他目标。 */
        @Bean
        Request.Options stockProductOptions() {
            return new Request.Options(500, TimeUnit.MILLISECONDS, 1000, TimeUnit.MILLISECONDS, false);
        }

        @Bean
        Logger.Level stockProductLogLevel() { return Logger.Level.NONE; }

        /** 使用项目 ObjectMapper，按 Result 泛型解码，并限制跨服务的响应体大小。 */
        @Bean
        Decoder stockProductDecoder(ObjectMapper json) {
            return (response, type) -> json.readValue(read(response.body()), json.constructType(type));
        }

        @Bean
        ErrorDecoder stockProductErrorDecoder(ObjectMapper json) {
            return (method, response) -> {
                // 区分商品服务明确返回的 SKU 不存在与路由/部署导致的普通 404。
                if (response.status() == 404) {
                    try {
                        var body = json.readTree(read(response.body()));
                        if (body != null && ErrorCode.SKU_NOT_FOUND.name().equals(body.path("code").asText())) {
                            return new BusinessException(ErrorCode.SKU_NOT_FOUND);
                        }
                    } catch (IOException invalid) {
                        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, invalid);
                    }
                }
                return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        new IOException("Product metadata endpoint returned HTTP " + response.status()));
            };
        }

        private static byte[] read(feign.Response.Body body) throws IOException {
            if (body == null) throw new IOException("Missing product metadata body");
            try (var input = body.asInputStream()) {
                byte[] bytes = input.readNBytes(4097);
                if (bytes.length > 4096) throw new IOException("Product metadata body too large");
                return bytes;
            }
        }
    }
}
