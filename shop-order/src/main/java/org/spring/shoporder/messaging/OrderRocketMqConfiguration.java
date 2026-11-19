package org.spring.shoporder.messaging;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.spring.shopmessaging.RocketMqTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shop.order.rocketmq.enabled", havingValue = "true")
class OrderRocketMqConfiguration {
    @Bean
    RocketMqTransport orderRocketMqTransport(
            @Value("${shop.order.rocketmq.endpoints}") String endpoints,
            @Value("${shop.order.rocketmq.topic}") String topic,
            @Value("${shop.order.rocketmq.tls:true}") boolean tls,
            @Value("${shop.order.rocketmq.access-key:}") String accessKey,
            @Value("${shop.order.rocketmq.secret-key:}") String secretKey) {
        if (accessKey.isBlank() != secretKey.isBlank()) {
            throw new IllegalArgumentException("Configure both RocketMQ credential fields");
        }
        if (!tls && !accessKey.isBlank()) {
            throw new IllegalArgumentException("RocketMQ credentials require TLS in shop-order");
        }
        var client = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints)
                .enableSsl(tls)
                .setRequestTimeout(Duration.ofSeconds(5));
        if (!accessKey.isBlank()) {
            client.setCredentialProvider(new StaticSessionCredentialsProvider(accessKey, secretKey));
        }
        return new RocketMqTransport(client.build(), topic);
    }

    @Bean(destroyMethod = "close")
    Producer orderRocketMqProducer(RocketMqTransport transport) throws ClientException {
        return transport.openProducer();
    }
}
