package org.spring.shopcommon.internalauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.util.List;
import java.util.Set;

/** 下游配置只有服务自身 audience、公钥与匿名路径，完全没有私钥/共享令牌字段。 */
@ConfigurationProperties(prefix = "shop.internal-auth", ignoreUnknownFields = false)
public record InternalAuthProperties(
        @DefaultValue("file:../infra/local-secrets/gateway-internal-public.pem") String publicKeyLocation,
        List<Endpoint> publicEndpoints) {
    public InternalAuthProperties { publicEndpoints = publicEndpoints == null ? List.of() : List.copyOf(publicEndpoints); }
    public record Endpoint(Set<String> methods, List<String> paths) { }
}
