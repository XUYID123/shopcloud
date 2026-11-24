package org.spring.shopmessaging.trade;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.spring.shopmessaging.MessageEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public final class TradeEvents {
    public static final String WAREHOUSE = "WH-SH-01";
    public static final String RESERVE = "InventoryReserveRequested";
    public static final String RELEASE = "InventoryReleaseRequested";
    public static final String RESERVED = "InventoryReserved";
    public static final String REJECTED = "InventoryRejected";
    public static final String RELEASED = "InventoryReleased";
    public static final String COMMAND_TOPIC = "shop-inventory-commands-v1";
    public static final String RESULT_TOPIC = "shop-order-results-v1";
    public static final String INVENTORY_GROUP = "shop-inventory-m3-v1";
    public static final String ORDER_GROUP = "shop-order-m3-v1";
    public static final String CONFIRM = "InventoryConfirmRequested";
    public static final String CONFIRMED = "InventoryConfirmed";
    public static final String INVENTORY_OPERATION_REJECTED = "InventoryOperationRejected";
    public static final String PAYMENT_CLOSE = "PaymentCloseRequested";
    public static final String PAYMENT_CLOSED = "PaymentClosed";
    public static final String PAYMENT_SUCCEEDED = "PaymentSucceeded";
    public static final String PAYMENT_REFUND = "PaymentRefundRequested";
    public static final String PAYMENT_REFUNDED = "PaymentRefundSucceeded";
    public static final String PAYMENT_REFUND_FAILED = "PaymentRefundFailed";
    public static final String PAYMENT_COMMAND_TOPIC = "shop-payment-commands-v1";
    public static final String PAYMENT_RESULT_TOPIC = "shop-payment-results-v1";
    public static final String PAYMENT_GROUP = "shop-pay-m4-v1";
    public static final String ORDER_PAYMENT_GROUP = "shop-order-pay-m4-v1";

    private final ObjectMapper json;

    public TradeEvents(ObjectMapper json) {
        this.json = json;
    }

    public MessageEvent encode(String type, String orderNo, String trace, Object payload) {
        var id = UUID.randomUUID();
        var envelope = new Envelope(id, type, 1, orderNo, 1, Instant.now().toString(), trace, json.valueToTree(payload));
        try {
            return new MessageEvent(id, 1, trace, json.writeValueAsBytes(envelope));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode trade event", failure);
        }
    }

    public Envelope decode(String tag, MessageEvent message) {
        try {
            var envelope = json.readValue(message.body(), Envelope.class);
            if (envelope == null || message.schemaVersion() != 1 || envelope.schemaVersion() != 1
                    || !message.eventId().equals(envelope.eventId()) || !tag.equals(envelope.eventType())
                    || !java.util.Objects.equals(message.traceId(), envelope.traceId())
                    || !validOrderNo(envelope.aggregateId()) || envelope.aggregateVersion() < 1
                    || envelope.occurredAt() == null || envelope.payload() == null || !envelope.payload().isObject()) {
                throw new IllegalArgumentException("Invalid trade event envelope");
            }
            Instant.parse(envelope.occurredAt());
            return envelope;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed trade event");
        }
    }

    public <T> T payload(Envelope envelope, Class<T> type) {
        try {
            return json.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Malformed trade event payload");
        }
    }

    public static boolean validOrderNo(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    }

    public static String requestHash(String warehouse, List<Item> items) {
        String content = warehouse + "|" + items.stream().sorted(Comparator.comparingLong(i -> Long.parseLong(i.skuId())))
                .map(item -> item.skuId() + ":" + item.quantity()).collect(Collectors.joining(","));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record Envelope(UUID eventId, String eventType, int schemaVersion, String aggregateId,
                           long aggregateVersion, String occurredAt, String traceId, JsonNode payload) { }
    public record Item(String skuId, int quantity) { }
    public record Reserve(String orderNo, String warehouseId, List<Item> items, String requestHash) { }
    public record Release(String orderNo, String reason) { }
    public record Batch(String skuId, String batchId, String batchNo, int quantity) { }
    public record Reserved(String orderNo, String reservationState, List<Batch> allocations) { }
    public record Rejected(String orderNo, String failureCode, List<String> failedSkuIds) { }
    public record Released(String orderNo, String reservationState) { }
    public record Confirm(String orderNo, String paymentNo) { }
    public record Confirmed(String orderNo, String reservationState) { }
    public record InventoryOperationRejected(String orderNo, String operation, String actualState, String failureCode) { }
    public record PaymentClose(String orderNo, String userId, long amountFen, String currency, String reason) { }
    public record PaymentClosed(String orderNo, String paymentNo, String channelState) { }
    public record PaymentSucceeded(String orderNo, String paymentNo, String providerTransactionNo, long amountFen,
                                   String currency, String paidAt, boolean afterConfirmedClose) { }
    public record PaymentRefund(String orderNo, String paymentNo, String refundNo, long amountFen, String reason) { }
    public record PaymentRefunded(String orderNo, String paymentNo, String refundNo, long amountFen, String refundedAt) { }
    public record PaymentRefundFailed(String orderNo, String paymentNo, String refundNo, String failureCode) { }
}
