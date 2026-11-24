package org.spring.shoporder;

import org.spring.shopcommon.result.GlobalExceptionHandler;
import org.spring.shoporder.cart.CartProductClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@EnableFeignClients(clients = CartProductClient.class)
@Import(GlobalExceptionHandler.class)
public class ShopOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopOrderApplication.class, args);
    }

}
