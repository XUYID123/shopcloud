package org.spring.shoporder.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.Response;
import feign.Retryer;
import feign.codec.Decoder;
import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

/** Shared settings for Feign child contexts with signed internal requests. */
public class OrderFeignSettings {
    @Bean
    Retryer orderRetryer() { return Retryer.NEVER_RETRY; }

    @Bean
    Request.Options orderOptions() {
        return new Request.Options(500, TimeUnit.MILLISECONDS, 1000, TimeUnit.MILLISECONDS, false);
    }

    @Bean
    Logger.Level orderLogLevel() { return Logger.Level.NONE; }

    public static RequestInterceptor credential(Resource resource, ScopedServiceJwt.Scope scope, boolean bindQuery) throws Exception {
        var key = ScopedServiceJwt.privateKey(resource);
        var clock = Clock.systemUTC();
        return request -> {
            String query = request.queryLine();
            String token = bindQuery
                    ? ScopedServiceJwt.signWithQuery(key, scope, request.method(), request.path(),
                            query.isEmpty() ? null : query.substring(1), clock)
                    : ScopedServiceJwt.sign(key, scope, request.method(), request.path(), clock);
            request.header(InternalJwtProtocol.HEADER, token);
        };
    }

    protected static Decoder boundedDecoder(ObjectMapper json, int limit) {
        return (response, type) -> json.readValue(body(response, limit), json.constructType(type));
    }

    protected static byte[] body(Response response, int limit) throws IOException {
        if (response.body() == null) throw new IOException("Missing order dependency response");
        try (var input = response.body().asInputStream()) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Order dependency response too large");
            return bytes;
        }
    }
}
