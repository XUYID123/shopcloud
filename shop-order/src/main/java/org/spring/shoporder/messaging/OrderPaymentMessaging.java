package org.spring.shoporder.messaging;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.spring.shopmessaging.MessageEvent;
import org.spring.shopmessaging.RocketMqTransport;
import org.spring.shopmessaging.trade.TradeEvents;
import org.spring.shoporder.order.OrderPaymentLifecycle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"shop.order.m3.enabled", "shop.order.m4.enabled", "shop.order.rocketmq.enabled"}, havingValue = "true")
class OrderPaymentMessaging {
    @Bean
    RocketMqTransport orderPaymentCommandTransport(ClientConfiguration connection) {
        return new RocketMqTransport(connection, TradeEvents.PAYMENT_COMMAND_TOPIC);
    }

    @Bean(destroyMethod = "close")
    @Lazy
    Producer orderPaymentProducer(@Qualifier("orderPaymentCommandTransport") RocketMqTransport transport) throws ClientException {
        return transport.openProducer();
    }

    @Bean(destroyMethod = "close")
    @Lazy
    PushConsumer orderPaymentResults(ClientConfiguration connection, OrderPaymentLifecycle lifecycle) throws ClientException {
        return new RocketMqTransport(connection, TradeEvents.PAYMENT_RESULT_TOPIC).subscribe(TradeEvents.ORDER_PAYMENT_GROUP,
                Set.of(TradeEvents.PAYMENT_SUCCEEDED, TradeEvents.PAYMENT_CLOSED,
                        TradeEvents.PAYMENT_REFUNDED, TradeEvents.PAYMENT_REFUND_FAILED), lifecycle::receive);
    }

    @Bean
    PaymentPipe orderPaymentPipe(@Qualifier("orderPaymentCommandTransport") RocketMqTransport transport,
                                 @Qualifier("orderPaymentProducer") ObjectProvider<Producer> producer,
                                 @Qualifier("orderPaymentResults") ObjectProvider<PushConsumer> consumer,
                                 OrderPaymentLifecycle lifecycle) {
        return new PaymentPipe(transport, producer, consumer, lifecycle);
    }

    record PaymentPipe(RocketMqTransport transport, ObjectProvider<Producer> producer,
                       ObjectProvider<PushConsumer> consumer, OrderPaymentLifecycle lifecycle) {
        String send(String tag, MessageEvent event) throws ClientException {
            return transport.send(producer.getObject(), tag, event);
        }

        @Scheduled(fixedDelay = 5000)
        void subscribe() { consumer.getObject(); }

        @Scheduled(fixedDelay = 1000)
        void expire() { lifecycle.expire(); }
    }
}
