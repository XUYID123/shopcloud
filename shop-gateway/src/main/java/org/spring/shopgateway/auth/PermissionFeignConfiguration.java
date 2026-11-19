package org.spring.shopgateway.auth;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import feign.codec.Decoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import java.util.concurrent.TimeUnit;

/**
 * 内部权限 Feign 客户端的专属配置，由 @FeignClient.configuration 引用。
 * 故意不加 @Configuration，避免组件扫描后把这些设置应用到所有客户端。
 * 这里只定义同步 HTTP 行为；把调用移出 Netty 线程由 RbacPermissionCache 负责。
 */
public class PermissionFeignConfiguration {
    /** 分别限制建立连接和等待响应的时间；false 关闭重定向，避免内部凭据随跳转发送。 */
    @Bean Request.Options permissionOptions() {
        // 总用户加载时限另由响应式 timeout 控制，不应把 readTimeout 当作整个网关请求时限。
        return new Request.Options(1000, TimeUnit.MILLISECONDS, 1500, TimeUnit.MILLISECONDS, false);
    }
    /** 失败交给 503 分支，不在 Feign 内自动叠加重试、延长锁占用和数据库压力。 */
    @Bean Retryer permissionRetryer() { return Retryer.NEVER_RETRY; }
    /** 禁止 Feign 输出请求/响应日志，避免内部凭据和用户权限文档进入日志。 */
    @Bean Logger.Level permissionLogLevel() { return Logger.Level.NONE; }
    /** 提供适用于当前 WebFlux 网关的解码器，不引入 MVC 环境才能使用的转换器。 */
    @Bean Decoder permissionDecoder(ObjectMapper mapper) {
        // WebFlux 不提供 MVC HttpMessageConverters，显式使用 Jackson 解码公共契约。
        return (response, type) -> {
            // 200 但没有正文仍是协议故障，不能转换成合法空权限或放行。
            if (response.body() == null) throw new java.io.IOException("Empty permission response");
            try (var input = response.body().asInputStream()) {
                // 最多读取限制值多一个字节来判断超限，避免先把任意大响应完整读进内存。
                byte[] bytes = input.readNBytes(262145);
                if (bytes.length > 262144) throw new java.io.IOException("Permission response too large");
                // 根据 Feign 方法的泛型返回类型恢复 Result<UserPermissionSnapshot>，不能丢失 data 类型。
                return mapper.readValue(bytes, mapper.constructType(type));
            }
        };
    }
}
