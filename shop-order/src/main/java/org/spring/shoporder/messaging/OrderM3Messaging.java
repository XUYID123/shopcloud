package org.spring.shoporder.messaging;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.spring.shopmessaging.JdbcMessageOutbox;
import org.spring.shopmessaging.OutboxWorkers;
import org.spring.shopmessaging.RocketMqTransport;
import org.spring.shopmessaging.trade.TradeEvents;
import org.spring.shoporder.order.OrderCartCleanup;
import org.spring.shoporder.order.OrderReservationResults;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
@ConditionalOnProperty(name = {"shop.order.m3.enabled", "shop.order.rocketmq.enabled"}, havingValue = "true")
class OrderM3Messaging {
    @Bean(destroyMethod = "close")
    @Lazy
    PushConsumer orderInventoryResults(ClientConfiguration connection, OrderReservationResults results) throws ClientException {
        return new RocketMqTransport(connection, TradeEvents.RESULT_TOPIC).subscribe(TradeEvents.ORDER_GROUP,
                Set.of(TradeEvents.RESERVED, TradeEvents.REJECTED, TradeEvents.RELEASED,
                        TradeEvents.CONFIRMED, TradeEvents.INVENTORY_OPERATION_REJECTED), results::receive);
    }

    @Bean
    OutboxWorkers orderReservationRelay(JdbcMessageOutbox outbox,
                                @Qualifier("orderRocketMqTransport") RocketMqTransport orderRocketMqTransport,
                                @Qualifier("orderRocketMqProducer") ObjectProvider<Producer> producer,
                                ObjectProvider<OrderPaymentMessaging.PaymentPipe> payments,
                                @Value("${shop.trade.outbox.workers:1}") int workers,
                                @Value("${shop.trade.outbox.poll-ms:500}") int pollMs) {
        return new OutboxWorkers(outbox, (tag, event) -> {
            if (TradeEvents.PAYMENT_CLOSE.equals(tag) || TradeEvents.PAYMENT_REFUND.equals(tag)) {
                return payments.getObject().send(tag, event);
            }
            return orderRocketMqTransport.send(producer.getObject(), tag, event);
        }, workers, pollMs);
    }

    @Bean
    Maintenance orderReservationMaintenance(@Qualifier("orderInventoryResults") ObjectProvider<PushConsumer> consumer,
                                            OrderCartCleanup cleanup) {
        return new Maintenance(consumer, cleanup);
    }

    record Maintenance(ObjectProvider<PushConsumer> consumer, OrderCartCleanup cleanup) {
        @Scheduled(fixedDelay = 5000)
        void subscribe() { consumer.getObject(); }

        @Scheduled(fixedDelay = 1000)
        void cleanCart() { cleanup.process(); }
    }
}
