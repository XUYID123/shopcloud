package org.spring.shoporder.order;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.servlet.http.HttpServletRequest;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.result.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = {"shop.order.m3.enabled", "shop.order.m4.enabled", "shop.order.m5.enabled"}, havingValue = "true")
public class FulfillmentController {
    private static final String ADMIN_ORDERS = "/api/v1/admin/orders";
    private static final String ADMIN_DETAIL = ADMIN_ORDERS + "/{orderNo}";
    private final OrderService orders;
    private final FulfillmentService fulfillment;

    FulfillmentController(OrderService orders, FulfillmentService fulfillment) {
        this.orders = orders;
        this.fulfillment = fulfillment;
    }

    @GetMapping(ADMIN_ORDERS)
    public ResponseEntity<Result<OrderService.Page>> list(HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "orderNo", required = false) String orderNo,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", required = false) String size) {
        return ok(orders.adminList(OrderController.owner(request), status, orderNo, cursor, size));
    }

    @GetMapping(ADMIN_DETAIL)
    public ResponseEntity<Result<FulfillmentService.AdminDetail>> detail(HttpServletRequest request,
            @PathVariable("orderNo") String orderNo) {
        OrderController.owner(request);
        return ok(fulfillment.detail(orderNo));
    }

    @PostMapping(path = ADMIN_DETAIL + "/shipments", consumes = "application/json")
    public ResponseEntity<Result<OrderView>> ship(HttpServletRequest request, @PathVariable("orderNo") String orderNo,
            @RequestBody ShipInput input) {
        var result = fulfillment.ship(orderNo, OrderController.owner(request), OrderController.idempotencyKey(request),
                input.carrierCode(), input.trackingNo());
        return ResponseEntity.status(result.created() ? 201 : 200).cacheControl(CacheControl.noStore())
                .body(Result.success(result.order()));
    }

    @PutMapping(path = "/api/v1/orders/{orderNo}/receipt", consumes = "application/json")
    public ResponseEntity<Result<FulfillmentService.Receipt>> receive(HttpServletRequest request,
            @PathVariable("orderNo") String orderNo, @RequestBody ReceiptInput input) {
        OrderController.idempotencyKey(request);
        if (!Boolean.TRUE.equals(input.received())) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        return ok(fulfillment.receive(orderNo, OrderController.owner(request)));
    }

    private static <T> ResponseEntity<Result<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(data));
    }

    public record ShipInput(@JsonDeserialize(using = PurchaseItems.StringId.class) String carrierCode,
                            @JsonDeserialize(using = PurchaseItems.StringId.class) String trackingNo) { }
    public record ReceiptInput(Boolean received) { }
}
