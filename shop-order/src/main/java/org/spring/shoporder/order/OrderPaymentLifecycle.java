package org.spring.shoporder.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.spring.shopcommon.checkout.PaymentContext;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.logging.BizLog;
import org.spring.shopcommon.trace.TraceContext;
import org.spring.shopmessaging.JdbcMessageInbox;
import org.spring.shopmessaging.JdbcMessageOutbox;
import org.spring.shopmessaging.MessageEvent;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(name = {"shop.order.m3.enabled", "shop.order.m4.enabled"}, havingValue = "true")
public class OrderPaymentLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OrderPaymentLifecycle.class);
    private final OrderPaymentStore payments;
    private final OrderStore orders;
    private final TradeEvents events;
    private final JdbcMessageOutbox outbox;
    private final JdbcMessageInbox inbox;
    private final TransactionTemplate writes;

    OrderPaymentLifecycle(OrderPaymentStore payments, OrderStore orders, TradeEvents events, JdbcMessageOutbox outbox,
                          DataSource source, PlatformTransactionManager manager) {
        this.payments = payments;
        this.orders = orders;
        this.events = events;
        this.outbox = outbox;
        inbox = new JdbcMessageInbox(source);
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public PaymentContext context(long user, String orderNo) {
        validateId(orderNo);
        var context = database(() -> payments.context(orderNo, user));
        if (context == null) throw error(ErrorCode.NOT_FOUND);
        return context;
    }

    @BizLog(action = "ORDER_CANCEL_REQUEST", bizId = "#result.data.cancellationNo")
    public Created cancel(long user, String orderNo, String key, String reason) {
        validateId(orderNo);
        if (reason != null && reason.length() > 200) throw error(ErrorCode.VALIDATION_ERROR);
        return database(() -> writes.execute(status -> {
            var order = payments.lock(orderNo);
            if (order == null || order.user() != user) throw error(ErrorCode.NOT_FOUND);
            var previous = payments.cancellation(orderNo, user);
            if (previous != null) {
                if (previous.key().equals(key) && !Objects.equals(previous.view().reason(), reason)) {
                    throw error(ErrorCode.IDEMPOTENCY_CONFLICT);
                }
                return new Created(previous.view(), false);
            }
            if (!"UNPAID".equals(order.status()) && !"PREPARING".equals(order.status())) throw error(ErrorCode.STATE_CONFLICT);
            payments.requestCancel(UUID.randomUUID().toString(), orderNo, user, key, reason);
            startClose(order, "USER_CANCEL");
            return new Created(payments.cancellation(orderNo, user).view(), true);
        }));
    }

    public Cancellation cancellation(long user, String orderNo, String number) {
        validateId(orderNo);
        validateId(number);
        var row = database(() -> payments.cancellation(orderNo, user));
        if (row == null || !number.equals(row.view().cancellationNo())) throw error(ErrorCode.NOT_FOUND);
        return row.view();
    }

    public void expire() {
        expire(true, "PAYMENT_TIMEOUT");
        expire(false, "PREPARING_TIMEOUT");
    }

    private void expire(boolean unpaid, String reason) {
        for (int i = 0; i < 4; i++) {
            boolean found = Boolean.TRUE.equals(writes.execute(status -> {
                var order = payments.due(unpaid);
                if (order == null) return false;
                startClose(order, reason);
                return true;
            }));
            if (!found) return;
        }
    }

    private void startClose(OrderPaymentStore.Row row, String reason) {
        payments.startClose(row.number(), reason);
        send(TradeEvents.PAYMENT_CLOSE, row.number(), new TradeEvents.PaymentClose(
                row.number(), Long.toString(row.user()), row.amount(), row.currency(), reason));
    }

    public void receive(String tag, MessageEvent message) {
        var event = events.decode(tag, message);
        String trace = event.traceId() == null ? TraceContext.newId() : event.traceId();
        try (var scope = TraceContext.open(trace)) {
            boolean first = inbox.consumeOnce(TradeEvents.ORDER_PAYMENT_GROUP, event.eventId(), () -> applyPayment(event));
            log.info("event=ORDER_PAYMENT_RESULT orderNo={} tag={} eventId={} firstDelivery={}",
                    event.aggregateId(), tag, event.eventId(), first);
        }
    }

    private void applyPayment(TradeEvents.Envelope event) {
        String number = event.aggregateId();
        var row = payments.lock(number);
        if (row == null) throw invalidEvent();
        switch (event.eventType()) {
            case TradeEvents.PAYMENT_CLOSED -> {
                var payload = events.payload(event, TradeEvents.PaymentClosed.class);
                matching(row, payload.orderNo(), payload.paymentNo());
                if (!"CLOSED".equals(payload.channelState())) throw invalidEvent();
                if ("CLOSING".equals(row.status())) completeClose(row, payload.paymentNo());
            }
            case TradeEvents.PAYMENT_SUCCEEDED -> success(row, events.payload(event, TradeEvents.PaymentSucceeded.class));
            case TradeEvents.PAYMENT_REFUNDED -> {
                var payload = events.payload(event, TradeEvents.PaymentRefunded.class);
                matching(row, payload.orderNo(), payload.paymentNo());
                if (row.paymentNo() == null || !row.paymentNo().equals(payload.paymentNo()) || row.refundNo() == null
                        || !row.refundNo().equals(payload.refundNo()) || row.amount() != payload.amountFen()) throw invalidEvent();
                Instant.parse(payload.refundedAt());
                if (!"REFUNDED".equals(row.paymentStatus())) payments.finishRefund(number, true);
            }
            case TradeEvents.PAYMENT_REFUND_FAILED -> {
                var payload = events.payload(event, TradeEvents.PaymentRefundFailed.class);
                matching(row, payload.orderNo(), payload.paymentNo());
                if (row.paymentNo() == null || !row.paymentNo().equals(payload.paymentNo()) || row.refundNo() == null
                        || !row.refundNo().equals(payload.refundNo())) throw invalidEvent();
                if ("REFUNDING".equals(row.paymentStatus())) {
                    payments.finishRefund(number, false);
                    log.warn("event=PAYMENT_REFUND_REQUIRES_REVIEW orderNo={} refundNo={}", number, row.refundNo());
                }
            }
            default -> throw invalidEvent();
        }
    }

    private void success(OrderPaymentStore.Row row, TradeEvents.PaymentSucceeded fact) {
        matching(row, fact.orderNo(), fact.paymentNo());
        if (!TradeEvents.validOrderNo(fact.paymentNo()) || !TradeEvents.validOrderNo(fact.providerTransactionNo())
                || row.amount() != fact.amountFen() || !row.currency().equals(fact.currency())) throw invalidEvent();
        Instant paidAt = Instant.parse(fact.paidAt());
        if ("CLOSED".equals(row.status()) || (fact.afterConfirmedClose() && "CLOSING".equals(row.status()))) {
            if ("CLOSING".equals(row.status())) completeClose(row, fact.paymentNo());
            startRefund(row, fact.paymentNo(), paidAt, "LATE_PAYMENT", false);
            return;
        }
        if ("UNPAID".equals(row.status()) || "CLOSING".equals(row.status())) {
            if (fact.afterConfirmedClose()) throw invalidEvent();
            payments.completePaid(row.number(), fact.paymentNo(), paidAt);
            orders.returnQuota(row.user());
            send(TradeEvents.CONFIRM, row.number(), new TradeEvents.Confirm(row.number(), fact.paymentNo()));
        } else if ("PREPARING".equals(row.status())) {
            throw invalidEvent();
        }
    }

    private void completeClose(OrderPaymentStore.Row row, String paymentNo) {
        payments.completeClose(row.number(), paymentNo);
        orders.returnQuota(row.user());
        send(TradeEvents.RELEASE, row.number(), new TradeEvents.Release(row.number(), "PAYMENT_CLOSED"));
    }

    private void startRefund(OrderPaymentStore.Row row, String paymentNo, Instant paid, String reason, boolean inventoryFailure) {
        if (row.refundNo() != null) return;
        String refundNo = UUID.randomUUID().toString();
        payments.startRefund(row.number(), paymentNo, refundNo, paid, reason, inventoryFailure);
        send(TradeEvents.PAYMENT_REFUND, row.number(),
                new TradeEvents.PaymentRefund(row.number(), paymentNo, refundNo, row.amount(), reason));
    }

    /** Called within the existing inventory-result inbox transaction. */
    boolean applyInventory(TradeEvents.Envelope event) {
        String tag = event.eventType();
        if (!TradeEvents.CONFIRMED.equals(tag) && !TradeEvents.INVENTORY_OPERATION_REJECTED.equals(tag)
                && !TradeEvents.RELEASED.equals(tag)) return false;
        var row = payments.lock(event.aggregateId());
        if (row == null) throw invalidEvent();
        switch (tag) {
            case TradeEvents.CONFIRMED -> {
                var payload = events.payload(event, TradeEvents.Confirmed.class);
                matching(row, payload.orderNo(), null);
                if (!"CONFIRMED".equals(payload.reservationState())) throw invalidEvent();
                if ("PAID".equals(row.status()) && "SUCCEEDED".equals(row.paymentStatus())
                        && !"CONFIRMED".equals(row.inventory())) payments.setInventory(row.number(), "CONFIRMED");
            }
            case TradeEvents.RELEASED -> {
                var payload = events.payload(event, TradeEvents.Released.class);
                matching(row, payload.orderNo(), null);
                if (!"RELEASED".equals(payload.reservationState())) throw invalidEvent();
                if (!"CLOSED".equals(row.status())) return false;
                if ("RELEASE_PENDING".equals(row.inventory())) payments.setInventory(row.number(), "RELEASED");
            }
            case TradeEvents.INVENTORY_OPERATION_REJECTED -> {
                var payload = events.payload(event, TradeEvents.InventoryOperationRejected.class);
                matching(row, payload.orderNo(), null);
                if ("CONFIRM".equals(payload.operation()) && "PAID".equals(row.status())
                        && !"CONFIRMED".equals(row.inventory()) && "SUCCEEDED".equals(row.paymentStatus())) {
                    startRefund(row, row.paymentNo(), payments.paidAt(row.number()), "INVENTORY_EXCEPTION", true);
                } else if ("RELEASE".equals(payload.operation()) && "CLOSED".equals(row.status())
                        && "RELEASE_PENDING".equals(row.inventory())) {
                    payments.setInventory(row.number(), "EXCEPTION");
                    log.warn("event=ORDER_INVENTORY_RELEASE_REJECTED orderNo={}", row.number());
                }
            }
            default -> throw invalidEvent();
        }
        return true;
    }

    private void send(String tag, String number, Object payload) {
        String trace = MDC.get(TraceContext.MDC_KEY);
        outbox.enqueue(tag, events.encode(tag, number, trace == null ? TraceContext.newId() : trace, payload));
    }

    private static void matching(OrderPaymentStore.Row row, String orderNo, String paymentNo) {
        if (!row.number().equals(orderNo) || (paymentNo != null && (!TradeEvents.validOrderNo(paymentNo)
                || (row.paymentNo() != null && !row.paymentNo().equals(paymentNo))))) throw invalidEvent();
    }

    private static <T> T database(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException
                 | CannotCreateTransactionException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    private static void validateId(String value) {
        if (!TradeEvents.validOrderNo(value)) throw error(ErrorCode.VALIDATION_ERROR);
    }

    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
    private static IllegalArgumentException invalidEvent() { return new IllegalArgumentException("Invalid payment result"); }

    public record Cancellation(String cancellationNo, String orderNo, String status, String reason, OffsetDateTime requestedAt,
                               OffsetDateTime completedAt, String failureCode, String orderUri) { }
    public record Created(Cancellation data, boolean created) { }
}
