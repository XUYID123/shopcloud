package org.spring.shopgateway.web;

import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/** Writes the validated client address after Gateway's outbound proxy header filters. */
@Component
final class TrustedClientIpHeadersFilter implements HttpHeadersFilter, Ordered {
    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        String clientIp = exchange.getAttribute(GatewayRequestFilter.CLIENT_IP);
        if (clientIp == null) {
            return input;
        }
        HttpHeaders trusted = new HttpHeaders();
        trusted.putAll(input);
        trusted.set(GatewayRequestHeaders.FORWARDED_FOR, clientIp);
        return trusted;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
