package org.spring.shopinventory.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.spring.shopmessaging.JdbcMessageOutbox;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
class ReservationInfrastructure {
    @Bean
    TradeEvents inventoryTradeEvents(ObjectMapper json) { return new TradeEvents(json); }

    @Bean
    JdbcMessageOutbox inventoryOutbox(DataSource source) { return new JdbcMessageOutbox(source); }
}
