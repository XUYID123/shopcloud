package org.spring.shoppay.payment;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.spring.shopcommon.checkout.PaymentContext;
import org.spring.shopcommon.checkout.PurchaseItems;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(name = "shop.pay.enabled", havingValue = "true")
public class PaymentService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Set<String> REFUND_REASONS = Set.of("LATE_PAYMENT", "INVENTORY_EXCEPTION");
    private final PaymentStore store;
    private final PaymentOrderClient orders;
    private final MockCallbackCodec mock;
    private final TradeEvents events;
    private final JdbcMessageOutbox outbox;
    private final JdbcMessageInbox inbox;
    private final TransactionTemplate transactions;

    PaymentService(PaymentStore store, PaymentOrderClient orders, MockCallbackCodec mock, TradeEvents events,
                   JdbcMessageOutbox outbox, DataSource source, PlatformTransactionManager manager) {
        this.store = store;
        this.orders = orders;
        this.mock = mock;
        this.events = events;
        this.outbox = outbox;
        inbox = new JdbcMessageInbox(source);
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @BizLog(action = "PAYMENT_SESSION_CREATE", bizId = "#result.data.paymentNo")
    public Created<PaymentViews.Session> create(long user, String orderNo, String provider, String key) {
        validId(orderNo);
        if (!"MOCK".equals(provider)) throw error(ErrorCode.VALIDATION_ERROR);
        var previous = replay(user, orderNo, key);
        if (previous != null) return previous;
        try {
            var order = orderContext(user, orderNo);
            requirePayable(order);
            return database(() -> transactions.execute(status -> {
                var control = store.claim(orderNo, user, order.amountFen(), order.currency());
                var existing = replay(user, orderNo, key);
                if (existing != null) return existing;
                requireOrder(control, user, order.amountFen(), order.currency());
                if (!"OPEN".equals(control.state()) || control.closeRequested()) throw error(ErrorCode.STATE_CONFLICT);
                requirePayable(order);
                var payment = store.byOrder(orderNo);
                if (payment != null) {
                    store.bind(user, key, payment.paymentNo());
                    return new Created<>(view(payment), false);
                }
                String number = UUID.randomUUID().toString();
                store.create(number, orderNo, order.expiresAt().toInstant());
                store.bind(user, key, number);
                return new Created<>(view(store.byNumber(number)), true);
            }));
        } catch (DuplicateKeyException | BusinessException failure) {
            var committed = replay(user, orderNo, key);
            if (committed != null) return committed;
            throw failure;
        }
    }

    public PaymentViews.Session find(long user, String paymentNo) {
        validId(paymentNo);
        var result = database(() -> store.byNumber(paymentNo));
        if (result == null || result.userId() != user) throw error(ErrorCode.NOT_FOUND);
        return view(result);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PaymentViews.Control reconciliation(String orderNo) {
        validId(orderNo);
        return database(() -> {
            var control = store.control(orderNo);
            if (control == null) throw error(ErrorCode.NOT_FOUND);
            var payment = store.byOrder(orderNo);
            String status = control.refundStatus() == null
                    ? payment == null ? ("CLOSED".equals(control.state()) ? "CLOSED" : "NONE") : payment.status()
                    : "REFUNDED";
            return new PaymentViews.Control(orderNo, control.state(), status, payment == null ? null : payment.paymentNo(),
                    control.amountFen(), control.currency(), control.closeRequested(), control.refundStatus());
        });
    }

    public void callback(byte[] body, String timestamp, String nonce, String signature) {
        var verified = mock.verify(body, timestamp, nonce, signature);
        var fact = verified.payload();
        try {
            database(() -> transactions.execute(status -> {
                var session = store.byNumber(fact.paymentNo());
                if (session == null) throw MockCallbackCodec.bad();
                var control = store.lock(session.orderNo());
                session = store.byNumber(fact.paymentNo());
                validateFact(session, fact);
                var generated = store.mockEvent(fact.paymentNo());
                if (generated != null && !java.util.Arrays.equals(generated.body(), body)) {
                    throw MockCallbackCodec.bad();
                }
                String hash = store.callbackDigest(fact.eventId());
                String usedNonce = store.nonceEvent(verified.nonce());
                if ((hash != null && !hash.equals(verified.digest()))
                        || (usedNonce != null && !usedNonce.equals(fact.eventId()))) throw MockCallbackCodec.bad();
                if (hash == null) store.storeCallback(fact.eventId(), verified.digest(), fact.paymentNo());
                if (usedNonce == null) store.storeNonce(verified.nonce(), fact.eventId());
                settle(control, session, fact, trace());
                return null;
            }));
        } catch (DuplicateKeyException conflicting) {
            throw MockCallbackCodec.bad();
        }
    }

    public Created<PaymentViews.Event> createMockEvent(String paymentNo, String type) {
        validId(paymentNo);
        if (!"SUCCEEDED".equals(type)) throw error(ErrorCode.VALIDATION_ERROR);
        return database(() -> transactions.execute(status -> {
            var payment = store.byNumber(paymentNo);
            if (payment == null) throw error(ErrorCode.NOT_FOUND);
            var control = store.lock(payment.orderNo());
            var existing = store.mockEvent(paymentNo);
            if (existing != null) return new Created<>(view(existing), false);
            if (!"OPEN".equals(control.state()) || control.closeRequested()) throw error(ErrorCode.STATE_CONFLICT);
            String eventId = UUID.randomUUID().toString();
            Instant when = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            var event = new PaymentStore.MockEvent(eventId, paymentNo, mock.encode(eventId, paymentNo, control.amountFen(), when),
                    when, trace());
            store.insertMockEvent(event);
            return new Created<>(view(event), true);
        }));
    }

    public PaymentViews.Event mockEvent(String paymentNo, String eventId) {
        validId(paymentNo);
        validId(eventId);
        var event = database(() -> store.mockEvent(paymentNo));
        if (event == null || !event.id().equals(eventId)) throw error(ErrorCode.NOT_FOUND);
        return view(event);
    }

    void deliverMockCallbacks() {
        for (var event : store.dueCallbacks()) {
            try (var scope = TraceContext.open(event.traceId() == null ? TraceContext.newId() : event.traceId())) {
                var proof = mock.sign(event.body());
                callback(event.body(), proof.timestamp(), proof.nonce(), proof.signature());
                store.delivered(event.id());
            } catch (RuntimeException failure) {
                store.callbackRetry(event.id());
                log.warn("event=MOCK_CALLBACK_RETRY eventId={} errorType={}", event.id(), failure.getClass().getSimpleName());
            }
        }
    }

    void receive(String tag, MessageEvent message) {
        var event = events.decode(tag, message);
        String trace = event.traceId() == null ? TraceContext.newId() : event.traceId();
        try (var scope = TraceContext.open(trace)) {
            boolean first = inbox.consumeOnce(TradeEvents.PAYMENT_GROUP, message.eventId(), () -> {
                switch (tag) {
                    case TradeEvents.PAYMENT_CLOSE -> close(event.aggregateId(), events.payload(event, TradeEvents.PaymentClose.class));
                    case TradeEvents.PAYMENT_REFUND -> refund(event.aggregateId(), events.payload(event, TradeEvents.PaymentRefund.class));
                    default -> throw new IllegalArgumentException("Unexpected payment tag");
                }
            });
            log.info("event=PAYMENT_COMMAND orderNo={} eventId={} tag={} firstDelivery={}",
                    event.aggregateId(), message.eventId(), tag, first);
        }
    }

    private void close(String orderNo, TradeEvents.PaymentClose close) {
        long user = PurchaseItems.positiveId(close.userId());
        if (!orderNo.equals(close.orderNo()) || !"CNY".equals(close.currency()) || close.amountFen() < 0
                || close.amountFen() > 1_000_000_000 || close.reason() == null || close.reason().isBlank()) throw badMessage();
        var control = store.claim(orderNo, user, close.amountFen(), close.currency());
        requireOrder(control, user, close.amountFen(), close.currency());
        if ("CLOSED".equals(control.state())) return;
        store.markCloseRequested(orderNo);
        if ("PAID".equals(control.state())) return;
        var session = store.byOrder(orderNo);
        if (session != null) {
            var fact = store.mockEvent(session.paymentNo());
            if (fact != null) {
                settle(control, session, mock.parse(fact.body()), trace());
                return;
            }
        }
        store.close(orderNo);
        send(TradeEvents.PAYMENT_CLOSED, orderNo,
                new TradeEvents.PaymentClosed(orderNo, session == null ? null : session.paymentNo(), "CLOSED"));
    }

    private void refund(String orderNo, TradeEvents.PaymentRefund command) {
        if (!orderNo.equals(command.orderNo()) || !TradeEvents.validOrderNo(command.refundNo())
                || !TradeEvents.validOrderNo(command.paymentNo()) || command.reason() == null
                || !REFUND_REASONS.contains(command.reason())) throw badMessage();
        var control = store.lock(orderNo);
        var payment = store.byOrder(orderNo);
        if (control == null || payment == null || !"SUCCEEDED".equals(payment.status())
                || !payment.paymentNo().equals(command.paymentNo()) || control.amountFen() != command.amountFen()) throw badMessage();
        String existing = store.refundNumber(orderNo);
        if (existing != null) {
            if (!existing.equals(command.refundNo())) throw badMessage();
            return;
        }
        var when = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.refund(orderNo, payment.paymentNo(), command.refundNo(), control.amountFen(), command.reason(), when);
        send(TradeEvents.PAYMENT_REFUNDED, orderNo, new TradeEvents.PaymentRefunded(
                orderNo, payment.paymentNo(), command.refundNo(), command.amountFen(), when.toString()));
    }

    private void settle(PaymentStore.Control control, PaymentStore.Session session, MockCallbackCodec.Payload fact, String trace) {
        validateFact(session, fact);
        if (session.txn() != null) {
            if (!session.txn().equals(fact.providerTransactionNo()) || !session.paidAt().equals(
                    fact.paidInstant().truncatedTo(ChronoUnit.MILLIS))) throw MockCallbackCodec.bad();
            return;
        }
        boolean late = "CLOSED".equals(control.state());
        Instant paid = fact.paidInstant().truncatedTo(ChronoUnit.MILLIS);
        store.paid(session.orderNo(), session.paymentNo(), fact.providerTransactionNo(), paid, late);
        outbox.enqueue(TradeEvents.PAYMENT_SUCCEEDED, events.encode(TradeEvents.PAYMENT_SUCCEEDED, session.orderNo(), trace,
                new TradeEvents.PaymentSucceeded(session.orderNo(), session.paymentNo(), fact.providerTransactionNo(),
                        session.amountFen(), session.currency(), paid.toString(), late)));
    }

    private static void validateFact(PaymentStore.Session session, MockCallbackCodec.Payload fact) {
        if (!session.paymentNo().equals(fact.paymentNo()) || session.amountFen() != fact.amountFen()
                || !session.currency().equals(fact.currency())
                || fact.paidInstant().isBefore(session.expiry().minusSeconds(905))) throw MockCallbackCodec.bad();
    }

    private PaymentContext orderContext(long user, String orderNo) {
        try {
            var response = orders.context(orderNo, user);
            var order = response == null ? null : response.getData();
            if (order == null || !"OK".equals(response.getCode()) || !orderNo.equals(order.orderNo())
                    || !Long.toString(user).equals(order.userId()) || !"CNY".equals(order.currency())
                    || order.amountFen() < 0 || order.amountFen() > 1_000_000_000) throw error(ErrorCode.DEPENDENCY_UNAVAILABLE);
            return order;
        } catch (FeignException unavailable) {
            throw error(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static void requirePayable(PaymentContext order) {
        if (!"UNPAID".equals(order.orderStatus()) || order.expiresAt() == null
                || !order.expiresAt().toInstant().isAfter(Instant.now())) throw error(ErrorCode.STATE_CONFLICT);
    }

    private static void requireOrder(PaymentStore.Control row, long user, long amount, String currency) {
        if (row.userId() != user || row.amountFen() != amount || !row.currency().equals(currency)) throw badMessage();
    }

    private Created<PaymentViews.Session> replay(long user, String orderNo, String key) {
        var payment = database(() -> store.byRequest(user, key));
        if (payment == null) return null;
        if (!orderNo.equals(payment.orderNo())) throw error(ErrorCode.IDEMPOTENCY_CONFLICT);
        return new Created<>(view(payment), false);
    }

    private void send(String tag, String orderNo, Object payload) {
        outbox.enqueue(tag, events.encode(tag, orderNo, trace(), payload));
    }

    private static String trace() { return MDC.get(TraceContext.MDC_KEY); }

    private static PaymentViews.Session view(PaymentStore.Session row) {
        return new PaymentViews.Session(row.paymentNo(), row.orderNo(), "MOCK", row.status(), row.amountFen(), row.currency(),
                OffsetDateTime.ofInstant(row.expiry(), ZONE), new PaymentViews.ClientParams(row.paymentNo()));
    }

    private static PaymentViews.Event view(PaymentStore.MockEvent row) {
        return new PaymentViews.Event(row.id(), row.paymentNo(), "SUCCEEDED", OffsetDateTime.ofInstant(row.createdAt(), ZONE));
    }

    private static <T> T database(Supplier<T> action) {
        try {
            return action.get();
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException
                 | CannotCreateTransactionException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    private static void validId(String id) {
        if (!TradeEvents.validOrderNo(id)) throw error(ErrorCode.VALIDATION_ERROR);
    }

    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
    private static IllegalArgumentException badMessage() { return new IllegalArgumentException("Invalid payment command"); }

    public record Created<T>(T data, boolean created) { }
}
