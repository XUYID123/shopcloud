package org.spring.shoporder.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.trace.TraceContext;
import org.spring.shopmessaging.JdbcMessageInbox;
import org.spring.shopmessaging.MessageEvent;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "shop.order.m3.enabled", havingValue = "true")
public class OrderReservationResults {
    private static final Logger log = LoggerFactory.getLogger(OrderReservationResults.class);
    private static final Set<String> FAILURE_CODES = Set.of("STOCK_NOT_ENOUGH", "BATCH_UNAVAILABLE");
    private final OrderStore store;
    private final TradeEvents events;
    private final JdbcMessageInbox inbox;
    private final ObjectProvider<OrderPaymentLifecycle> lifecycle;

    OrderReservationResults(OrderStore store, TradeEvents events, DataSource source,
                            ObjectProvider<OrderPaymentLifecycle> lifecycle) {
        this.store = store;
        this.events = events;
        inbox = new JdbcMessageInbox(source);
        this.lifecycle = lifecycle;
    }

    public void receive(String tag, MessageEvent message) {
        var event = events.decode(tag, message);
        String trace = event.traceId() == null ? TraceContext.newId() : event.traceId();
        try (var scope = TraceContext.open(trace)) {
            boolean processed = inbox.consumeOnce(TradeEvents.ORDER_GROUP, event.eventId(), () -> apply(event));
            log.info("event=ORDER_RESERVATION_RESULT orderNo={} eventId={} tag={} firstDelivery={}",
                    event.aggregateId(), event.eventId(), tag, processed);
        }
    }

    private void apply(TradeEvents.Envelope event) {
        var payment = lifecycle.getIfAvailable();
        if (payment != null && payment.applyInventory(event)) return;
        String orderNo = event.aggregateId();
        var order = store.forResult(orderNo);
        if (order == null) throw new IllegalArgumentException("Unknown order in inventory result");
        if (!"PREPARING".equals(order.status()) && !TradeEvents.RELEASED.equals(event.eventType())) return;
        switch (event.eventType()) {
            case TradeEvents.RESERVED -> {
                var reserved = events.payload(event, TradeEvents.Reserved.class);
                if (!orderNo.equals(reserved.orderNo()) || !"RESERVED".equals(reserved.reservationState())) throw invalid();
                verifyAllocation(order, reserved);
                if (store.markReserved(orderNo)) store.queueCartCleanup(orderNo);
            }
            case TradeEvents.REJECTED -> {
                var rejected = events.payload(event, TradeEvents.Rejected.class);
                if (!orderNo.equals(rejected.orderNo()) || rejected.failureCode() == null
                        || !FAILURE_CODES.contains(rejected.failureCode())
                        || rejected.failedSkuIds() == null || rejected.failedSkuIds().isEmpty()) throw invalid();
                var expected = store.items(order.itemSnapshot()).stream().map(item -> item.product().skuId()).toList();
                if (!expected.containsAll(rejected.failedSkuIds())) throw invalid();
                if (store.markFailed(orderNo, "REJECTED", rejected.failureCode())) store.returnQuota(order.userId());
            }
            case TradeEvents.RELEASED -> {
                var released = events.payload(event, TradeEvents.Released.class);
                if (!orderNo.equals(released.orderNo()) || !"RELEASED".equals(released.reservationState())) throw invalid();
                if (store.markReleasedBeforePayment(orderNo)) store.returnQuota(order.userId());
            }
            default -> throw invalid();
        }
    }

    private void verifyAllocation(OrderStore.EventOrder order, TradeEvents.Reserved reserved) {
        if (reserved.allocations() == null || reserved.allocations().isEmpty() || reserved.allocations().size() > 2000) {
            throw invalid();
        }
        var expected = new HashMap<String, Integer>();
        for (var item : store.items(order.itemSnapshot())) {
            expected.put(item.product().skuId(), item.quantity());
        }
        var seenBatches = new HashSet<String>();
        var actual = new HashMap<String, Integer>();
        for (var batch : reserved.allocations()) {
            if (batch == null || !expected.containsKey(batch.skuId()) || batch.batchNo() == null
                    || batch.batchNo().isBlank() || batch.batchNo().length() > 64 || batch.quantity() < 1
                    || batch.quantity() > 100) throw invalid();
            PurchaseItems.positiveId(batch.batchId());
            if (!seenBatches.add(batch.batchId())) throw invalid();
            actual.merge(batch.skuId(), batch.quantity(), Integer::sum);
        }
        if (!expected.equals(actual)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid inventory result");
    }
}
