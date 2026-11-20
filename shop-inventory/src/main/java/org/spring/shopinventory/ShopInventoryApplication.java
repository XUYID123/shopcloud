package org.spring.shopinventory;

import org.spring.shopcommon.result.GlobalExceptionHandler;
import org.spring.shopinventory.product.StockProductClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;

/** 库存服务入口，商品元数据通过内部 OpenFeign 客户端读取。 */
@SpringBootApplication
@EnableFeignClients(clients = StockProductClient.class)
@Import(GlobalExceptionHandler.class)
public class ShopInventoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopInventoryApplication.class, args);
    }
}
