package org.spring.shoporder.order;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.servlet.http.HttpServletRequest;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.spring.shopcommon.result.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Collections;

@RestController
@RequestMapping("/api/v1/orders")
@ConditionalOnProperty(name = "shop.order.m3.enabled", havingValue = "true")
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<Result<OrderView>> create(HttpServletRequest request, @RequestBody CreateInput input) {
        OrderView order = orders.create(owner(request), idempotencyKey(request), input.quoteId());
        HttpStatus status = "PREPARING".equals(order.orderStatus()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .location(URI.create("/api/v1/orders/" + order.orderNo())).body(Result.success(order));
    }

    static String idempotencyKey(HttpServletRequest request) {
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1 || !keys.get(0).matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return keys.get(0);
    }

    @GetMapping
    public ResponseEntity<Result<OrderService.Page>> list(HttpServletRequest request,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", required = false) String size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(orders.list(owner(request), status, cursor, size)));
    }

    @GetMapping("/{orderNo}")
    public ResponseEntity<Result<OrderView>> detail(HttpServletRequest request, @PathVariable("orderNo") String orderNo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(orders.detail(owner(request), orderNo)));
    }

    static long owner(HttpServletRequest request) {
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"user".equals(identity.kind()) || identity.userId() == null) throw new BusinessException(ErrorCode.FORBIDDEN);
        return Long.parseLong(identity.userId());
    }

    public record CreateInput(@JsonDeserialize(using = PurchaseItems.StringId.class) String quoteId) { }
}
