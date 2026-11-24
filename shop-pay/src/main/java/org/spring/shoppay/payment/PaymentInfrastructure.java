package org.spring.shoppay.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.spring.shopmessaging.JdbcMessageOutbox;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableFeignClients(clients = PaymentOrderClient.class)
@EnableScheduling
@ConditionalOnProperty(name = "shop.pay.enabled", havingValue = "true")
class PaymentInfrastructure {
    @Bean
    JdbcMessageOutbox paymentOutbox(DataSource source) { return new JdbcMessageOutbox(source); }

    @Bean
    TradeEvents paymentEvents(ObjectMapper json) { return new TradeEvents(json); }

    @Bean
    MockRelay mockCallbackRelay(PaymentService payments) { return new MockRelay(payments); }

    record MockRelay(PaymentService payments) {
        @Scheduled(fixedDelay = 500)
        void deliver() { payments.deliverMockCallbacks(); }
    }
}
