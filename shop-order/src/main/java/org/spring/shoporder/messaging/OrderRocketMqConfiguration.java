package org.spring.shoporder.messaging;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.spring.shopmessaging.RocketMqConnection;
import org.spring.shopmessaging.RocketMqTransport;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.order.rocketmq.enabled", havingValue = "true")
class OrderRocketMqConfiguration {
    @Bean
    ClientConfiguration orderRocketMqClientConfiguration(
            @Value("${shop.order.rocketmq.endpoints}") String endpoints,
            @Value("${shop.order.rocketmq.tls:true}") boolean tls,
            @Value("${shop.order.rocketmq.access-key:}") String accessKey,
            @Value("${shop.order.rocketmq.secret-key:}") String secretKey) {
        return RocketMqConnection.configure(endpoints, tls, accessKey, secretKey);
    }

    @Bean
    RocketMqTransport orderRocketMqTransport(ClientConfiguration orderRocketMqClientConfiguration,
                                            @Value("${shop.order.rocketmq.topic:shop-inventory-commands-v1}") String topic,
                                            @Value("${shop.order.m3.enabled:false}") boolean tradeEnabled) {
        if (tradeEnabled && !TradeEvents.COMMAND_TOPIC.equals(topic)) {
            throw new IllegalArgumentException("M3 requires the inventory command topic");
        }
        return new RocketMqTransport(orderRocketMqClientConfiguration, topic);
    }

    @Bean(destroyMethod = "close")
    @Lazy
    Producer orderRocketMqProducer(@Qualifier("orderRocketMqTransport") RocketMqTransport transport) throws ClientException {
        return transport.openProducer();
    }
}
