package org.spring.shopinventory.reservation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spring.shopmessaging.trade.TradeEvents;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
        "spring.docker.compose.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "shop.internal-auth.public-key-location=file:../infra/security/test-fixtures/internal-test-public.pem",
        "shop.inventory.signing-private-key-location=file:../infra/security/test-fixtures/internal-test-private.pem"
})
class ReservationIntegrationTests {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReservationCommands commands;
    @Autowired private ReservationQueries queries;
    @Autowired private TradeEvents events;
    @MockitoSpyBean private ReservationStore store;
    private final long sku = ThreadLocalRandom.current().nextLong(100_000_000L, 900_000_000L);
    private final LocalDate today = LocalDate.now(ReservationCommands.BUSINESS_ZONE);
    private final List<String> orders = new ArrayList<>();

    @AfterEach
    void clean() {
        for (String order : orders) {
            jdbc.update("DELETE FROM rocketmq_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(CONVERT(body USING utf8mb4),'$.aggregateId'))=?", order);
            jdbc.update("DELETE FROM stock_ledger WHERE operation_no=?", order);
            jdbc.update("DELETE FROM reservation_item WHERE order_no=?", order);
            jdbc.update("DELETE FROM inventory_reservation WHERE order_no=?", order);
        }
        jdbc.update("DELETE FROM stock_batch WHERE sku_id IN (?,?)", sku, sku+1);
        jdbc.update("DELETE FROM sku_stock WHERE sku_id IN (?,?)", sku, sku+1);
    }

    @Test
    void selectsFefoSkipsExpiredAndBlockedAndDeduplicatesAcrossEventIds() {
        addStock(sku, 25);
        addBatch(sku, "today", 6, today, "SELLABLE");
        addBatch(sku, "late", 8, today.plusDays(9), "SELLABLE");
        addBatch(sku, "blocked", 5, today.plusDays(1), "BLOCKED");
        addBatch(sku, "early", 6, today.plusDays(5), "SELLABLE");
        String order = newOrder();
        var command = reserve(order, item(sku, 7));
        var message = events.encode(TradeEvents.RESERVE, order, "m3.fefo", command);
        commands.receive(TradeEvents.RESERVE, message);
        commands.receive(TradeEvents.RESERVE, message);
        commands.receive(TradeEvents.RESERVE, events.encode(TradeEvents.RESERVE, order, null, command));
        var result = queries.find(order);
        assertThat(result.state()).isEqualTo("RESERVED");
        assertThat(result.allocations()).extracting(ReservationViews.BatchAllocation::batchNo).containsExactly("early", "late");
        assertThat(result.allocations()).extracting(ReservationViews.BatchAllocation::quantity).containsExactly(6, 1);
        assertThat(balance(sku, "reserved")).isEqualTo(7);
        assertThat(balance(sku, "available")).isEqualTo(18);
        assertThat(queries.allocation(order).shippable()).isFalse();
        assertThat(outbox(order)).isEqualTo(1);
    }

    @Test
    void shortageInSecondSkuLeavesFirstSkuAndLedgerUntouched() {
        addStock(sku, 10);
        addBatch(sku, "first", 10, today.plusDays(3), "SELLABLE");
        addStock(sku + 1, 1);
        addBatch(sku + 1, "second", 1, today.plusDays(3), "SELLABLE");
        String order = newOrder();
        sendReserve(order, item(sku+1, 2), item(sku, 4));
        assertThat(queries.find(order).failureCode()).isEqualTo("STOCK_NOT_ENOUGH");
        assertThat(queries.find(order).allocations()).isEmpty();
        assertThat(balance(sku, "available")).isEqualTo(10);
        assertThat(ledgers(order)).isZero();
        assertThat(outbox(order)).isEqualTo(1);
    }

    @Test
    void lateReserveRespectsTombstoneAndReleaseReturnsOnlySellableStock() {
        addStock(sku, 10);
        addBatch(sku, "soon", 5, today.plusDays(1), "SELLABLE");
        addBatch(sku, "later", 5, today.plusDays(2), "SELLABLE");
        String closed = newOrder();
        sendRelease(closed);
        sendReserve(closed, item(sku, 4));
        assertThat(queries.find(closed).state()).isEqualTo("RELEASED");
        assertThat(balance(sku, "available")).isEqualTo(10);
        String held = newOrder();
        sendReserve(held, item(sku, 8));
        jdbc.update("UPDATE stock_batch SET quality_status='BLOCKED' WHERE sku_id=? AND batch_no='soon'", sku);
        sendRelease(held);
        sendRelease(held);
        assertThat(balance(sku, "reserved")).isZero();
        assertThat(balance(sku, "available")).isEqualTo(5);
        assertThat(balance(sku, "unavailable")).isEqualTo(5);
        assertThat(queries.find(held).state()).isEqualTo("RELEASED");
        assertThat(outbox(held)).isEqualTo(2);
    }

    @Test
    void concurrentOrdersCannotReserveMoreThanAvailable() throws Exception {
        addStock(sku, 10);
        addBatch(sku, "race", 10, today.plusDays(5), "SELLABLE");
        String one = newOrder();
        String two = newOrder();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> sendReserve(one, item(sku, 7)));
            var b = workers.submit(() -> sendReserve(two, item(sku, 7)));
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
        assertThat(List.of(queries.find(one).state(), queries.find(two).state()))
                .containsExactlyInAnyOrder("RESERVED", "REJECTED");
        assertThat(balance(sku, "reserved")).isEqualTo(7);
        assertThat(balance(sku, "available")).isEqualTo(3);
    }

    @Test
    void unexpectedFailureRollsBackStockInboxAndOutboxAndAllowsRetry() {
        addStock(sku, 10);
        addBatch(sku, "retry", 10, today.plusDays(5), "SELLABLE");
        String order = newOrder();
        var message = events.encode(TradeEvents.RESERVE, order, null, reserve(order, item(sku, 6)));
        doThrow(new IllegalStateException("test interruption")).doCallRealMethod()
                .when(store).reserveBatch(eq(order), anyLong(), any(), anyInt());
        assertThatThrownBy(() -> commands.receive(TradeEvents.RESERVE, message)).isInstanceOf(IllegalStateException.class);
        assertThat(balance(sku, "available")).isEqualTo(10);
        assertThat(store.find(order)).isNull();
        assertThat(outbox(order)).isZero();
        commands.receive(TradeEvents.RESERVE, message);
        assertThat(queries.find(order).state()).isEqualTo("RESERVED");
        assertThat(balance(sku, "reserved")).isEqualTo(6);
    }

    @Test
    void confirmedInventoryMovesOnceAndCannotBeReleased() {
        addStock(sku, 10);
        addBatch(sku, "confirm", 10, today.plusDays(5), "SELLABLE");
        String order = newOrder();
        sendReserve(order, item(sku, 4));
        var confirm = new TradeEvents.Confirm(order, "test-payment");
        commands.receive(TradeEvents.CONFIRM, events.encode(TradeEvents.CONFIRM, order, null, confirm));
        commands.receive(TradeEvents.CONFIRM, events.encode(TradeEvents.CONFIRM, order, null, confirm));
        sendRelease(order);
        assertThat(queries.find(order).state()).isEqualTo("CONFIRMED");
        assertThat(queries.allocation(order).shippable()).isTrue();
        assertThat(balance(sku, "available")).isEqualTo(6);
        assertThat(balance(sku, "reserved")).isZero();
        assertThat(balance(sku, "committed")).isEqualTo(4);
        assertThat(outbox(order)).isEqualTo(3);
    }

    private void addStock(long id, int available) {
        jdbc.update("INSERT INTO sku_stock(sku_id,warehouse_id,available) VALUES (?,'WH-SH-01',?)", id, available);
    }

    private void addBatch(long id, String number, int available, LocalDate deadline, String quality) {
        jdbc.update("""
                INSERT INTO stock_batch(sku_id,warehouse_id,batch_no,manufactured_on,expires_on,quality_status,initial_quantity,available)
                VALUES (?,'WH-SH-01',?,?,?,?,?,?)
                """, id, number, today.minusDays(3), deadline, quality, available, available);
    }

    private long balance(long skuId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM sku_stock WHERE sku_id=? AND warehouse_id='WH-SH-01'", Long.class, skuId);
    }

    private int outbox(String order) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM rocketmq_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(CONVERT(body USING utf8mb4),'$.aggregateId'))=?
                """, Integer.class, order);
    }

    private int ledgers(String order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_ledger WHERE operation_no=?", Integer.class, order);
    }

    private String newOrder() {
        String order = UUID.randomUUID().toString();
        orders.add(order);
        return order;
    }

    private void sendReserve(String order, TradeEvents.Item... items) {
        commands.receive(TradeEvents.RESERVE, events.encode(TradeEvents.RESERVE, order, null, reserve(order, items)));
    }

    private void sendRelease(String order) {
        commands.receive(TradeEvents.RELEASE, events.encode(TradeEvents.RELEASE, order, null,
                new TradeEvents.Release(order, "TEST_TIMEOUT")));
    }

    private static TradeEvents.Item item(long sku, int quantity) {
        return new TradeEvents.Item(Long.toString(sku), quantity);
    }

    private static TradeEvents.Reserve reserve(String order, TradeEvents.Item... items) {
        return new TradeEvents.Reserve(order, TradeEvents.WAREHOUSE, List.of(items),
                TradeEvents.requestHash(TradeEvents.WAREHOUSE, List.of(items)));
    }
}
