package org.spring.shopinventory.reservation;

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
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "shop.inventory.rocketmq.enabled", havingValue = "true")
class InventoryM3Messaging {
    @Bean
    ClientConfiguration inventoryRocketMqConnection(
            @Value("${shop.inventory.rocketmq.endpoints}") String endpoints,
            @Value("${shop.inventory.rocketmq.tls:true}") boolean tls,
            @Value("${shop.inventory.rocketmq.access-key:}") String accessKey,
            @Value("${shop.inventory.rocketmq.secret-key:}") String secretKey) {
        return RocketMqConnection.configure(endpoints, tls, accessKey, secretKey);
    }

    @Bean
    RocketMqTransport inventoryResultsTransport(ClientConfiguration connection) {
        return new RocketMqTransport(connection, TradeEvents.RESULT_TOPIC);
    }

    @Bean(destroyMethod = "close")
    @Lazy
    Producer inventoryResultsProducer(RocketMqTransport transport) throws ClientException {
        return transport.openProducer();
    }

    @Bean(destroyMethod = "close")
    @Lazy
    PushConsumer inventoryCommandsConsumer(ClientConfiguration connection, ReservationCommands commands) throws ClientException {
        return new RocketMqTransport(connection, TradeEvents.COMMAND_TOPIC).subscribe(TradeEvents.INVENTORY_GROUP,
                Set.of(TradeEvents.RESERVE, TradeEvents.RELEASE, TradeEvents.CONFIRM), commands::receive);
    }

    @Bean
    OutboxWorkers inventoryResultsRelay(JdbcMessageOutbox outbox, RocketMqTransport transport,
                                        ObjectProvider<Producer> producer,
                                        @Value("${shop.trade.outbox.workers:1}") int workers,
                                        @Value("${shop.trade.outbox.poll-ms:500}") int pollMs) {
        return new OutboxWorkers(outbox, (tag, event) -> transport.send(producer.getObject(), tag, event), workers, pollMs);
    }

    @Bean
    Subscription inventorySubscription(ObjectProvider<PushConsumer> consumer) { return new Subscription(consumer); }

    record Subscription(ObjectProvider<PushConsumer> consumer) {
        @Scheduled(fixedDelay = 5000)
        void subscribe() { consumer.getObject(); }
    }
}
