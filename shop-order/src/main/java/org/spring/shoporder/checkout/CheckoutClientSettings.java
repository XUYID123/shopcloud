package org.spring.shoporder.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shoporder.client.OrderFeignSettings;
import org.springframework.context.annotation.Bean;

import java.io.IOException;

/** Registered only in the two checkout Feign child contexts. */
class CheckoutClientSettings extends OrderFeignSettings {
    private static final int MAX_BODY_BYTES = 32 * 1024;

    @Bean
    Decoder checkoutDecoder(ObjectMapper json) {
        return boundedDecoder(json, MAX_BODY_BYTES);
    }

    static ErrorDecoder errors(ObjectMapper json, ErrorCode... expected) {
        return (method, response) -> {
            try {
                if (response.status() == 404 || response.status() == 422) {
                    var payload = json.readTree(body(response, MAX_BODY_BYTES));
                    for (var error : expected) {
                        if (payload != null && response.status() == error.getHttpStatus()
                                && error.name().equals(payload.path("code").asText())) {
                            return new BusinessException(error);
                        }
                    }
                }
            } catch (IOException invalid) {
                // Report only the protocol failure. The body may contain private address information.
                return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        };
    }
}
