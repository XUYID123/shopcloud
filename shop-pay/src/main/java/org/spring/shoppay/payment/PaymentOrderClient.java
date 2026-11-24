package org.spring.shoppay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import org.spring.shopcommon.checkout.PaymentContext;
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
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

@FeignClient(name = "shop-order", contextId = "paymentOrder", url = "${shop.pay.order-url:}",
        configuration = PaymentOrderClient.Security.class)
interface PaymentOrderClient {
    @GetMapping("/internal/v1/orders/{orderNo}/payment-context")
    Result<PaymentContext> context(@PathVariable("orderNo") String orderNo, @RequestParam("userId") long userId);

    class Security {
        @Bean
        RequestInterceptor paymentOrderCredential(@Value("${shop.pay.order-private-key}") Resource resource) throws Exception {
            var key = ScopedServiceJwt.privateKey(resource);
            var scope = new ScopedServiceJwt.Scope("shop-pay", "shop-order", "payment:order:read");
            var clock = Clock.systemUTC();
            return request -> request.header(InternalJwtProtocol.HEADER,
                    ScopedServiceJwt.signWithQuery(key, scope, request.method(), request.path(),
                            request.queryLine().substring(1), clock));
        }

        @Bean
        Retryer paymentOrderRetryer() { return Retryer.NEVER_RETRY; }

        @Bean
        Request.Options paymentOrderOptions() {
            return new Request.Options(500, TimeUnit.MILLISECONDS, 1000, TimeUnit.MILLISECONDS, false);
        }

        @Bean
        Decoder paymentOrderDecoder(ObjectMapper json) {
            return (response, type) -> json.readValue(body(response), json.constructType(type));
        }

        @Bean
        ErrorDecoder paymentOrderErrors(ObjectMapper json) {
            return (method, response) -> {
                if (response.status() == 404) {
                    try {
                        var payload = json.readTree(body(response));
                        if (payload != null && "NOT_FOUND".equals(payload.path("code").asText())) {
                            return new BusinessException(ErrorCode.NOT_FOUND);
                        }
                    } catch (IOException invalid) {
                        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                    }
                }
                return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            };
        }

        private static byte[] body(feign.Response response) throws IOException {
            if (response.body() == null) throw new IOException("Missing order response");
            try (var input = response.body().asInputStream()) {
                byte[] bytes = input.readNBytes(8193);
                if (bytes.length > 8192) throw new IOException("Order response too large");
                return bytes;
            }
        }
    }
}
