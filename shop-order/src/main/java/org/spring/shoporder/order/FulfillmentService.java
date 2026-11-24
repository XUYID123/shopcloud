package org.spring.shoporder.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import feign.FeignException;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.spring.shopcommon.logging.BizLog;
import org.spring.shopmessaging.trade.TradeEvents;
import org.spring.shoporder.order.FulfillmentInventoryClient.Allocation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;

@Service
@ConditionalOnProperty(name = {"shop.order.m3.enabled", "shop.order.m4.enabled", "shop.order.m5.enabled"}, havingValue = "true")
public class FulfillmentService {
    private final OrderStore orders;
    private final FulfillmentStore fulfillment;
    private final FulfillmentInventoryClient inventory;
    private final TransactionTemplate writes;

    FulfillmentService(OrderStore orders, FulfillmentStore fulfillment, FulfillmentInventoryClient inventory,
                       PlatformTransactionManager transactions) {
        this.orders = orders;
        this.fulfillment = fulfillment;
        this.inventory = inventory;
        writes = new TransactionTemplate(transactions);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    AdminDetail detail(String orderNo) {
        validateNumber(orderNo);
        var order = OrderService.database(() -> requireOrder(orderNo));
        var allocation = "PAID".equals(order.orderStatus()) && "CONFIRMED".equals(order.inventoryStatus())
                ? allocation(orderNo) : null;
        return new AdminDetail(order, allocation);
    }

    @BizLog(action = "ORDER_SHIP", bizId = "#p0")
    public ShipmentResult ship(String orderNo, long actor, String key, String carrier, String tracking) {
        validateNumber(orderNo);
        if (carrier == null || !carrier.matches("[A-Z0-9_-]{1,30}")
                || tracking == null || !tracking.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        var command = new ShipCommand(orderNo, actor, key, InternalJwtProtocol.digest(carrier + "/" + tracking),
                carrier, tracking);
        var previous = commitShipment(command, null);
        if (previous != null) return previous;

        final Allocation allocation;
        try {
            allocation = allocation(orderNo);
        } catch (BusinessException failure) {
            // A parallel request may have committed while the inventory call failed.
            previous = commitShipment(command, null);
            if (previous != null) return previous;
            throw failure;
        }
        return commitShipment(command, allocation);
    }

    @BizLog(action = "ORDER_RECEIVE", bizId = "#p0")
    public Receipt receive(String orderNo, long owner) {
        validateNumber(orderNo);
        return OrderService.database(() -> writes.execute(transaction -> {
            var order = fulfillment.lock(orderNo, owner);
            if (order == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            if ("COMPLETED".equals(order.status()) && order.receivedAt() != null) {
                return new Receipt(orderNo, true, order.receivedAt());
            }
            if (!"SHIPPED".equals(order.status())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            return new Receipt(orderNo, true, fulfillment.receive(orderNo));
        }));
    }

    private ShipmentResult commitShipment(ShipCommand command, Allocation allocation) {
        return OrderService.database(() -> writes.execute(transaction -> {
            String orderNo = command.orderNo();
            var state = fulfillment.lock(orderNo, null);
            if (state == null) throw new BusinessException(ErrorCode.NOT_FOUND);
            if ("SHIPPED".equals(state.status()) || "COMPLETED".equals(state.status())) {
                var order = requireOrder(orderNo);
                if (order.shipment() == null) throw new IllegalStateException("Missing shipment for fulfilled order");
                var hash = fulfillment.shipmentRequest(orderNo, command.actor(), command.key());
                if (hash == null) fulfillment.bindRequest(orderNo, command.actor(), command.key(), command.hash());
                else if (!hash.equals(command.hash())) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                return new ShipmentResult(false, order);
            }
            if (!"PAID".equals(state.status()) || !"CONFIRMED".equals(state.inventory())
                    || !"SUCCEEDED".equals(state.payment())) throw new BusinessException(ErrorCode.STATE_CONFLICT);
            if (allocation == null) return null;

            LocalDate today = LocalDate.now(OrderStore.BUSINESS_ZONE);
            if (!allocation.shippable() || !"CONFIRMED".equals(allocation.reservationState())
                    || allocation.allocations().isEmpty() || allocation.allocations().stream().anyMatch(batch ->
                    !"SELLABLE".equals(batch.qualityStatus()) || !batch.expiresOn().isAfter(today))) {
                throw new BusinessException(ErrorCode.STATE_CONFLICT);
            }
            verifyQuantities(requireOrder(orderNo), allocation);
            fulfillment.ship(orderNo, command.carrier(), command.tracking(), allocation.allocations());
            fulfillment.bindRequest(orderNo, command.actor(), command.key(), command.hash());
            return new ShipmentResult(true, requireOrder(orderNo));
        }));
    }

    private OrderView requireOrder(String orderNo) {
        var order = orders.adminDetail(orderNo);
        if (order == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return order.view();
    }

    private Allocation allocation(String orderNo) {
        try {
            var response = inventory.allocation(orderNo);
            var data = response == null ? null : response.getData();
            if (data == null || !"OK".equals(response.getCode()) || !orderNo.equals(data.orderNo())
                    || data.reservationState() == null || data.shippable() == null || data.allocations() == null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            var batchIds = new HashSet<String>();
            for (var batch : data.allocations()) {
                if (batch == null || !TradeEvents.validOrderNo(batch.skuId()) || !TradeEvents.validOrderNo(batch.batchId())
                        || !batchIds.add(batch.batchId()) || batch.batchNo() == null || batch.batchNo().isBlank()
                        || batch.quantity() < 1 || batch.quantity() > 100 || batch.expiresOn() == null
                        || !("SELLABLE".equals(batch.qualityStatus()) || "BLOCKED".equals(batch.qualityStatus()))) {
                    throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                }
            }
            return data;
        } catch (FeignException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static void verifyQuantities(OrderView order, Allocation allocation) {
        var expected = new HashMap<String, Integer>();
        var allocated = new HashMap<String, Integer>();
        order.items().forEach(line -> expected.put(line.skuId(), line.quantity()));
        allocation.allocations().forEach(batch -> allocated.merge(batch.skuId(), batch.quantity(), Integer::sum));
        if (!allocated.equals(expected)) throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    private static void validateNumber(String orderNo) {
        if (!TradeEvents.validOrderNo(orderNo)) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }

    private record ShipCommand(String orderNo, long actor, String key, String hash, String carrier, String tracking) { }
    record ShipmentResult(boolean created, OrderView order) { }
    record AdminDetail(@JsonUnwrapped OrderView order, @JsonInclude(JsonInclude.Include.NON_NULL) Allocation allocation) { }
    public record Receipt(String orderNo, boolean received, OffsetDateTime receivedAt) { }
}
