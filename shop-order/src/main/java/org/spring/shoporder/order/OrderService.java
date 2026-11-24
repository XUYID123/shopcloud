package org.spring.shoporder.order;

import org.slf4j.MDC;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.spring.shopcommon.logging.BizLog;
import org.spring.shopcommon.trace.TraceContext;
import org.spring.shopmessaging.JdbcMessageOutbox;
import org.spring.shopmessaging.trade.TradeEvents;
import org.spring.shoporder.admission.ClusterOrderAdmission;
import org.spring.shoporder.checkout.CheckoutQuoteService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(name = "shop.order.m3.enabled", havingValue = "true")
public class OrderService {
    private static final Set<String> STATUSES = Set.of(
            "PREPARING", "UNPAID", "CLOSING", "PAID", "SHIPPED", "COMPLETED", "CLOSED", "FAILED");
    private final OrderStore store;
    private final CheckoutQuoteService checkout;
    private final JdbcMessageOutbox outbox;
    private final TradeEvents events;
    private final ObjectProvider<ClusterOrderAdmission> admission;
    private final TransactionTemplate writes;

    OrderService(OrderStore store, CheckoutQuoteService checkout, JdbcMessageOutbox outbox, TradeEvents events,
                 PlatformTransactionManager transactions, ObjectProvider<ClusterOrderAdmission> admission) {
        this.store = store;
        this.checkout = checkout;
        this.outbox = outbox;
        this.events = events;
        this.admission = admission;
        writes = new TransactionTemplate(transactions);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @BizLog(action = "ORDER_CREATE", bizId = "#result.orderNo")
    public OrderView create(long userId, String key, String quoteId) {
        if (!TradeEvents.validOrderNo(quoteId)) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        String hash = InternalJwtProtocol.digest(quoteId);
        var previous = replay(userId, key, hash);
        if (previous != null) return previous;

        final String checkedVersion;
        final List<TradeEvents.Item> items;
        try {
            admission.ifAvailable(ClusterOrderAdmission::acquire);
            var quote = database(() -> store.quote(userId, quoteId, false));
            validateQuote(quote);
            var snapshots = store.items(quote.itemSnapshot());
            var requested = snapshots.stream()
                    .map(item -> new PurchaseItems.Item(item.product().skuId(), item.quantity())).toList();
            checkedVersion = checkout.currentVersion(userId, quote.addressId(), requested);
            items = requested.stream().map(item -> new TradeEvents.Item(item.skuId(), item.quantity())).toList();
        } catch (BusinessException failure) {
            var concurrent = replay(userId, key, hash);
            if (concurrent != null) return concurrent;
            throw failure;
        }

        return database(() -> writes.execute(status -> {
            store.lockQuota(userId);
            var concurrent = replay(userId, key, hash);
            if (concurrent != null) return concurrent;
            var quote = store.quote(userId, quoteId, true);
            validateQuote(quote);
            if (!quote.hash().equals(checkedVersion)) throw new BusinessException(ErrorCode.PRICE_CHANGED);
            if (!store.takeQuota(userId)) throw new BusinessException(ErrorCode.ORDER_LIMIT_EXCEEDED);
            String number = UUID.randomUUID().toString();
            store.create(number, userId, key, hash, quote);
            var command = new TradeEvents.Reserve(number, TradeEvents.WAREHOUSE, items,
                    TradeEvents.requestHash(TradeEvents.WAREHOUSE, items));
            outbox.enqueue(TradeEvents.RESERVE, events.encode(TradeEvents.RESERVE, number, MDC.get(TraceContext.MDC_KEY), command));
            return store.detail(userId, number).view();
        }));
    }

    public OrderView detail(long userId, String orderNo) {
        if (!TradeEvents.validOrderNo(orderNo)) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        var found = database(() -> store.detail(userId, orderNo));
        if (found == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return found.view();
    }

    public Page list(long userId, String status, String cursorToken, String size) {
        return page(userId, userId, status, null, status, cursorToken, size);
    }

    Page adminList(long actorId, String status, String orderNo, String cursorToken, String size) {
        if (orderNo != null && !TradeEvents.validOrderNo(orderNo)) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        String filter = (status == null ? "-" : status) + "/" + (orderNo == null ? "" : orderNo);
        return page(null, -actorId, status, orderNo, filter, cursorToken, size);
    }

    private Page page(Long userId, long cursorScope, String status, String orderNo, String filter,
                      String cursorToken, String size) {
        if (status != null && !STATUSES.contains(status)) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        if (size != null && !size.matches("([1-9]|[1-4][0-9]|50)")) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        int limit = size == null ? 20 : Integer.parseInt(size);
        OrderCursor cursor = OrderCursor.parse(cursorToken, cursorScope, filter);
        var rows = database(() -> store.list(userId, status, orderNo, cursor, limit + 1));
        if (rows.size() <= limit) return new Page(rows.stream().map(OrderStore.Stored::view).toList(), null, false);
        var last = rows.get(limit - 1);
        String next = new OrderCursor(last.view().createdAt().toInstant(), last.id()).encode(cursorScope, filter);
        return new Page(rows.subList(0, limit).stream().map(OrderStore.Stored::view).toList(), next, true);
    }

    private OrderView replay(long userId, String key, String hash) {
        var found = database(() -> store.replay(userId, key));
        if (found == null) return null;
        if (!hash.equals(found.hash())) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        return found.view();
    }

    private static void validateQuote(OrderStore.Quote quote) {
        if (quote == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (quote.consumedOrderNo() != null) throw new BusinessException(ErrorCode.QUOTE_USED);
        if (quote.expired()) throw new BusinessException(ErrorCode.QUOTE_EXPIRED);
    }

    static <T> T database(Supplier<T> action) {
        try {
            return action.get();
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                 | RecoverableDataAccessException | CannotCreateTransactionException failure) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    public record Page(List<OrderView> items, String nextCursor, boolean hasMore) { }
}
