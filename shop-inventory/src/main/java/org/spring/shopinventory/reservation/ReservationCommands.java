package org.spring.shopinventory.reservation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.trace.TraceContext;
import org.spring.shopmessaging.JdbcMessageInbox;
import org.spring.shopmessaging.JdbcMessageOutbox;
import org.spring.shopmessaging.MessageEvent;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

@Service
public class ReservationCommands {
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(ReservationCommands.class);
    private final ReservationStore store;
    private final TradeEvents events;
    private final JdbcMessageInbox inbox;
    private final JdbcMessageOutbox outbox;

    ReservationCommands(ReservationStore store, TradeEvents events, JdbcMessageOutbox outbox, DataSource source) {
        this.store = store;
        this.events = events;
        this.outbox = outbox;
        inbox = new JdbcMessageInbox(source);
    }

    public void receive(String tag, MessageEvent message) {
        var event = events.decode(tag, message);
        String trace = event.traceId() == null ? TraceContext.newId() : event.traceId();
        try (var scope = TraceContext.open(trace)) {
            boolean first = inbox.consumeOnce(TradeEvents.INVENTORY_GROUP, event.eventId(), () -> {
                switch (tag) {
                    case TradeEvents.RESERVE -> reserve(event, trace);
                    case TradeEvents.RELEASE -> release(event, trace);
                    case TradeEvents.CONFIRM -> confirm(event, trace);
                    default -> throw invalid();
                }
            });
            log.info("event=INVENTORY_RESERVATION_COMMAND orderNo={} eventId={} tag={} firstDelivery={}",
                    event.aggregateId(), event.eventId(), tag, first);
        }
    }

    private void reserve(TradeEvents.Envelope event, String trace) {
        var request = events.payload(event, TradeEvents.Reserve.class);
        String number = event.aggregateId();
        if (!number.equals(request.orderNo()) || !TradeEvents.WAREHOUSE.equals(request.warehouseId())
                || request.items() == null || request.items().isEmpty() || request.items().size() > 20
                || request.items().stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        var quantities = PurchaseItems.validated(request.items().stream()
                .map(item -> new PurchaseItems.Item(item.skuId(), item.quantity())).toList());
        String hash = TradeEvents.requestHash(request.warehouseId(), request.items());
        if (!hash.equals(request.requestHash())) throw invalid();

        var existing = store.lock(number);
        if (existing.hash() != null && !hash.equals(existing.hash())) throw invalid();
        if (!"PENDING".equals(existing.state())) return;
        var plan = new ArrayList<PlannedBatch>();
        var failures = new ArrayList<String>();
        String failure = plan(quantities, LocalDate.now(BUSINESS_ZONE), plan, failures);
        if (failure != null) {
            store.update(number, "REJECTED", hash, failure);
            outbox.enqueue(TradeEvents.REJECTED, events.encode(TradeEvents.REJECTED, number, trace,
                    new TradeEvents.Rejected(number, failure, List.copyOf(failures))));
            return;
        }
        quantities.forEach(store::reserveSku);
        for (var batch : plan) {
            store.reserveBatch(number, batch.sku(), batch.batch(), batch.quantity());
        }
        store.update(number, "RESERVED", hash, null);
        var allocations = plan.stream().map(batch -> new TradeEvents.Batch(Long.toString(batch.sku()),
                Long.toString(batch.batch().id()), batch.batch().number(), batch.quantity())).toList();
        outbox.enqueue(TradeEvents.RESERVED, events.encode(TradeEvents.RESERVED, number, trace,
                new TradeEvents.Reserved(number, "RESERVED", allocations)));
    }

    private String plan(SortedMap<Long, Integer> quantities, LocalDate today, List<PlannedBatch> plan, List<String> failures) {
        String failure = null;
        // Acquire each SKU's aggregate before any batch for it, in ascending SKU order.
        for (var line : quantities.entrySet()) {
            long sku = line.getKey();
            int needed = line.getValue();
            Long available = store.lockStock(sku);
            if (available == null || available < needed) {
                failures.add(Long.toString(sku));
                failure = "STOCK_NOT_ENOUGH";
                continue;
            }
            for (var batch : store.sellableBatches(sku, today)) {
                int take = (int) Math.min(needed, batch.available());
                plan.add(new PlannedBatch(sku, batch, take));
                needed -= take;
                if (needed == 0) break;
            }
            if (needed != 0) {
                failures.add(Long.toString(sku));
                if (failure == null) failure = "BATCH_UNAVAILABLE";
            }
        }
        return failure;
    }

    private void release(TradeEvents.Envelope event, String trace) {
        String number = event.aggregateId();
        var request = events.payload(event, TradeEvents.Release.class);
        if (!number.equals(request.orderNo()) || request.reason() == null
                || request.reason().isBlank() || request.reason().length() > 200) throw invalid();
        var existing = store.lock(number);
        switch (existing.state()) {
            case "RELEASED" -> { return; }
            case "CONFIRMED" -> {
                rejectOperation(number, "RELEASE", "CONFIRMED", trace);
                return;
            }
            case "RESERVED" -> releaseBatches(number);
            case "PENDING", "REJECTED" -> { }
            default -> throw invalid();
        }
        store.update(number, "RELEASED", existing.hash(), null);
        outbox.enqueue(TradeEvents.RELEASED, events.encode(TradeEvents.RELEASED, number, trace,
                new TradeEvents.Released(number, "RELEASED")));
    }

    private void releaseBatches(String orderNo) {
        applyBatches(orderNo, false);
    }

    private void confirm(TradeEvents.Envelope event, String trace) {
        String number = event.aggregateId();
        var request = events.payload(event, TradeEvents.Confirm.class);
        if (!number.equals(request.orderNo()) || !TradeEvents.validOrderNo(request.paymentNo())) throw invalid();
        var existing = store.lock(number);
        switch (existing.state()) {
            case "CONFIRMED" -> {
                if (!request.paymentNo().equals(store.confirmedPayment(number))) throw invalid();
                return;
            }
            case "RESERVED" -> {
                applyBatches(number, true);
                store.confirm(number, request.paymentNo());
                outbox.enqueue(TradeEvents.CONFIRMED, events.encode(TradeEvents.CONFIRMED, number, trace,
                        new TradeEvents.Confirmed(number, "CONFIRMED")));
            }
            case "PENDING" -> {
                store.update(number, "RELEASED", null, null);
                rejectOperation(number, "CONFIRM", "RELEASED", trace);
            }
            default -> rejectOperation(number, "CONFIRM", existing.state(), trace);
        }
    }

    private void rejectOperation(String number, String operation, String state, String trace) {
        outbox.enqueue(TradeEvents.INVENTORY_OPERATION_REJECTED,
                events.encode(TradeEvents.INVENTORY_OPERATION_REJECTED, number, trace,
                        new TradeEvents.InventoryOperationRejected(number, operation, state, "INVALID_RESERVATION_STATE")));
    }

    private void applyBatches(String orderNo, boolean confirm) {
        var items = store.items(orderNo);
        if (items.isEmpty()) throw new IllegalStateException("Reservation has no allocation");
        var today = LocalDate.now(BUSINESS_ZONE);
        long lockedSku = 0;
        for (var item : items) {
            if (item.skuId() != lockedSku) {
                lockedSku = item.skuId();
                if (store.lockStock(lockedSku) == null) throw new IllegalStateException("Missing reserved stock");
            }
            var batch = store.lockBatch(lockedSku, item.batchId());
            if (confirm) store.confirmBatch(orderNo, item, batch);
            else store.releaseBatch(orderNo, item, batch, batch.sellable(today));
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid reservation command"); }
    private record PlannedBatch(long sku, ReservationStore.Batch batch, int quantity) { }
}
