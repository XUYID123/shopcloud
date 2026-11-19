package org.spring.shopmessaging;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class RocketMqTransport {
    private static final Logger log = LoggerFactory.getLogger(RocketMqTransport.class);
    private static final String EVENT_ID = "shopEventId";
    private static final String SCHEMA_VERSION = "shopSchemaVersion";
    private static final String TRACE_ID = "shopTraceId";

    private final ClientServiceProvider provider = ClientServiceProvider.loadService();
    private final ClientConfiguration configuration;
    private final String topic;

    public RocketMqTransport(ClientConfiguration configuration, String topic) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.topic = requireName(topic, "topic");
    }

    public Producer openProducer() throws ClientException {
        return provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .setTopics(topic)
                .setMaxAttempts(2)
                .build();
    }

    public String send(Producer producer, String tag, MessageEvent event) throws ClientException {
        String eventId = event.eventId().toString();
        var message = provider.newMessageBuilder()
                .setTopic(topic)
                .setTag(requireName(tag, "tag"))
                .setKeys(eventId)
                .addProperty(EVENT_ID, eventId)
                .addProperty(SCHEMA_VERSION, Integer.toString(event.schemaVersion()))
                .setBody(event.body());
        if (event.traceId() != null) {
            message.addProperty(TRACE_ID, event.traceId());
        }
        return producer.send(message.build()).getMessageId().toString();
    }

    public PushConsumer subscribe(String group, String tag, Consumer<MessageEvent> handler) throws ClientException {
        requireName(group, "group");
        requireName(tag, "tag");
        Objects.requireNonNull(handler, "handler");
        return provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(group)
                .setSubscriptionExpressions(Map.of(topic, new FilterExpression(tag, FilterExpressionType.TAG)))
                .setMessageListener(message -> consume(message, group, handler))
                .build();
    }

    private ConsumeResult consume(MessageView message, String group, Consumer<MessageEvent> handler) {
        MessageEvent event = null;
        try {
            event = decode(message);
            handler.accept(event);
            return ConsumeResult.SUCCESS;
        } catch (RuntimeException failure) {
            log.warn("event=ROCKETMQ_CONSUME_FAILED topic={} group={} messageId={} eventId={} traceId={} errorType={}",
                    topic, group, message.getMessageId(),
                    event == null ? "-" : event.eventId(), event == null ? "-" : event.traceId(),
                    failure.getClass().getSimpleName());
            return ConsumeResult.FAILURE;
        }
    }

    private static MessageEvent decode(MessageView message) {
        Map<String, String> properties = message.getProperties();
        UUID id = UUID.fromString(properties.get(EVENT_ID));
        if (!message.getKeys().contains(id.toString())) {
            throw new IllegalArgumentException("Message key does not match event ID");
        }
        ByteBuffer buffer = message.getBody().asReadOnlyBuffer();
        if (buffer.remaining() > 256 * 1024) {
            throw new IllegalArgumentException("Message body exceeds the application limit");
        }
        byte[] body = new byte[buffer.remaining()];
        buffer.get(body);
        return new MessageEvent(id, Integer.parseInt(properties.get(SCHEMA_VERSION)), properties.get(TRACE_ID), body);
    }

    static String requireName(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid RocketMQ " + field);
        }
        return value;
    }
}
