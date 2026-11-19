package org.spring.shopgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关启动入口：接收外部请求，执行流量保护，再按路由转发到业务服务。
 * 配置类和过滤器放在当前包的子包下，由 Spring Boot 自动扫描注册。
 */
// 自定义 JWT GlobalFilter 承担认证，网关仅引入 Security JOSE 验签库。
@SpringBootApplication
public class ShopGatewayApplication {

    /**
     * 启动响应式 HTTP 服务并注册各组件；这里不按书写顺序直接调用过滤器。
     * 外部请求到达后，WebFlux/Gateway 根据两层过滤链和各自顺序调度执行。
     */
    public static void main(String[] args) {
        SpringApplication.run(ShopGatewayApplication.class, args);
    }

}
