package org.spring.shoppay.payment;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.spring.shopmessaging.JdbcMessageOutbox;
import org.spring.shopmessaging.OutboxWorkers;
import org.spring.shopmessaging.RocketMqConnection;
import org.spring.shopmessaging.RocketMqTransport;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"shop.pay.enabled", "shop.pay.rocketmq.enabled"}, havingValue = "true")
class PaymentMessaging {
    @Bean
    ClientConfiguration paymentConnection(@Value("${shop.pay.rocketmq.endpoints}") String endpoints,
            @Value("${shop.pay.rocketmq.tls:true}") boolean tls,
            @Value("${shop.pay.rocketmq.access-key:}") String accessKey,
            @Value("${shop.pay.rocketmq.secret-key:}") String secretKey) {
        return RocketMqConnection.configure(endpoints, tls, accessKey, secretKey);
    }

    @Bean
    RocketMqTransport paymentResults(ClientConfiguration connection) {
        return new RocketMqTransport(connection, TradeEvents.PAYMENT_RESULT_TOPIC);
    }

    @Bean(destroyMethod = "close")
    @Lazy
    Producer paymentProducer(RocketMqTransport transport) throws ClientException { return transport.openProducer(); }

    @Bean(destroyMethod = "close")
    @Lazy
    PushConsumer paymentCommands(ClientConfiguration connection, PaymentService payments) throws ClientException {
        return new RocketMqTransport(connection, TradeEvents.PAYMENT_COMMAND_TOPIC).subscribe(
                TradeEvents.PAYMENT_GROUP, Set.of(TradeEvents.PAYMENT_CLOSE, TradeEvents.PAYMENT_REFUND), payments::receive);
    }

    @Bean
    OutboxWorkers paymentRelay(JdbcMessageOutbox outbox, RocketMqTransport transport, ObjectProvider<Producer> producer,
                               @Value("${shop.trade.outbox.workers:1}") int workers,
                               @Value("${shop.trade.outbox.poll-ms:500}") int pollMs) {
        return new OutboxWorkers(outbox, (tag, event) -> transport.send(producer.getObject(), tag, event), workers, pollMs);
    }

    @Bean
    Subscription paymentSubscription(ObjectProvider<PushConsumer> consumer) { return new Subscription(consumer); }

    record Subscription(ObjectProvider<PushConsumer> consumer) {
        @Scheduled(fixedDelay = 5000)
        void subscribe() { consumer.getObject(); }
    }
}
