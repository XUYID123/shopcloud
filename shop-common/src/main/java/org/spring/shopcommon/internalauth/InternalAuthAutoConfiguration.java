package org.spring.shopcommon.internalauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import jakarta.servlet.DispatcherType;

/** 强制 Servlet starter，缺少公钥/服务名即启动失败；WebFlux 网关不安装此 Filter。 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(InternalAuthProperties.class)
public class InternalAuthAutoConfiguration {
    @Bean InternalJwtVerifier internalJwtVerifier(InternalAuthProperties properties, Environment environment, ResourceLoader loader) throws Exception {
        // 不额外开放 audience 配置项：强制验证自己的服务名，缺少名称或公钥即启动失败。
        String name = environment.getRequiredProperty("spring.application.name");
        // 只加载公钥，并限制输入体积；私钥不会作为下游配置或 Bean 注入。
        byte[] bytes;
        try (var input = loader.getResource(properties.publicKeyLocation()).getInputStream()) { bytes = input.readNBytes(16_385); }
        if (bytes.length > 16_384) throw new IllegalArgumentException("Internal public key too large");
        String pem = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII)
                .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        return new InternalJwtVerifier(key, name, Clock.systemUTC());
    }
    @Bean FilterRegistrationBean<InternalAuthenticationFilter> internalAuthenticationFilterRegistration(InternalJwtVerifier verifier,
            ObjectMapper mapper, InternalAuthProperties properties, Environment environment) {
        var registration = new FilterRegistrationBean<>(new InternalAuthenticationFilter(verifier, mapper, properties, environment.getRequiredProperty("spring.application.name")));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10); // 在 Trace Filter 之后，所有拒绝都有追踪 ID。
        registration.setAsyncSupported(true);
        // 普通请求、异步、错误和 Servlet 内部分派均纳入认证边界，避免只保护 Controller 的盲区。
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR, DispatcherType.FORWARD, DispatcherType.INCLUDE);
        registration.addUrlPatterns("/*"); return registration;
    }
}
