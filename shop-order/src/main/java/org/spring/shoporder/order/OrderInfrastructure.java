package org.spring.shoporder.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.spring.shopmessaging.JdbcMessageOutbox;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.order.m3.enabled", havingValue = "true")
class OrderInfrastructure {
    @Bean
    TradeEvents orderTradeEvents(ObjectMapper json) { return new TradeEvents(json); }

    @Bean
    JdbcMessageOutbox orderOutbox(DataSource source) { return new JdbcMessageOutbox(source); }

    @Bean
    OrderCartCleanup orderCartCleanup(JdbcClient jdbc, OrderStore store, PlatformTransactionManager manager) {
        return new OrderCartCleanup(jdbc, store, manager);
    }
}
