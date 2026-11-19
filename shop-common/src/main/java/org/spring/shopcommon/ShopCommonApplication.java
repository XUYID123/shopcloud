package org.spring.shopcommon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * common 模块保留的历史示例/测试启动类，不表示应把 common 部署为独立微服务。
 * 正常业务服务通过 Maven 依赖复用公共类，并显式导入需要的 MVC 配置；网关不加载 MVC 处理器。
 */
@SpringBootApplication
public class ShopCommonApplication {

    /** 仅在主动运行本模块示例时启动容器，业务模块引入依赖不会自动执行此 main。 */
	public static void main(String[] args) {
		SpringApplication.run(ShopCommonApplication.class, args);
	}

}
