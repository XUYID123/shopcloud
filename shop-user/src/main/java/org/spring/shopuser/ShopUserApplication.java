package org.spring.shopuser;

import org.spring.shopcommon.result.GlobalExceptionHandler;
import org.spring.shopuser.config.AddressProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * 用户服务独立启动入口，加载本模块的 MVC、MyBatis、Flyway、业务 Redis 与服务发现配置。
 * common 是依赖模块而非父级扫描包，通过 @Import 明确注册其 MVC 全局异常处理器。
 */
@SpringBootApplication
@EnableConfigurationProperties(AddressProperties.class)
@Import(GlobalExceptionHandler.class)
public class ShopUserApplication {

    /** 启动 Spring 容器和 HTTP 服务；实际请求由过滤器与 Controller 接收，不在 main 中串行执行。 */
    public static void main(String[] args) {
        SpringApplication.run(ShopUserApplication.class, args);
    }

}
