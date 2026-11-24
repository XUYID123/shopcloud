package org.spring.shoporder.order;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.servlet.http.HttpServletRequest;
import org.spring.shopcommon.checkout.PaymentContext;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.spring.shopcommon.internalauth.InternalServiceEndpoint;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopcommon.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPatternParser;

import java.net.URI;
import java.time.Clock;
import java.util.Collections;

@RestController
@ConditionalOnProperty(name = {"shop.order.m3.enabled", "shop.order.m4.enabled"}, havingValue = "true")
public class OrderPaymentController {
    private static final String CANCEL = "/api/v1/orders/{orderNo}/cancellations";
    private static final String CONTEXT = "/internal/v1/orders/{orderNo}/payment-context";
    private final OrderPaymentLifecycle orders;

    OrderPaymentController(OrderPaymentLifecycle orders) { this.orders = orders; }

    @PostMapping(path = CANCEL, consumes = "application/json")
    public ResponseEntity<Result<OrderPaymentLifecycle.Cancellation>> cancel(HttpServletRequest request,
            @PathVariable("orderNo") String number, @RequestBody Cancel input) {
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1 || !keys.get(0).matches("[A-Za-z0-9._:-]{16,128}")) throw invalid();
        var result = orders.cancel(owner(request), number, keys.get(0), input.reason());
        var data = result.data();
        return ResponseEntity.status(result.created() ? 202 : 200).cacheControl(CacheControl.noStore())
                .location(URI.create(data.orderUri() + "/cancellations/" + data.cancellationNo())).body(Result.success(data));
    }

    @GetMapping(CANCEL + "/{cancellationNo}")
    public ResponseEntity<Result<OrderPaymentLifecycle.Cancellation>> find(HttpServletRequest request,
            @PathVariable("orderNo") String orderNo, @PathVariable("cancellationNo") String number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(orders.cancellation(owner(request), orderNo, number)));
    }

    @GetMapping(CONTEXT)
    public ResponseEntity<Result<PaymentContext>> context(HttpServletRequest request, @PathVariable("orderNo") String orderNo) {
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"service".equals(identity.kind())) throw new BusinessException(ErrorCode.FORBIDDEN);
        String[] users = request.getParameterValues("userId");
        if (users == null || users.length != 1) throw invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(orders.context(PurchaseItems.positiveId(users[0]), orderNo)));
    }

    private static long owner(HttpServletRequest request) {
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"user".equals(identity.kind()) || identity.userId() == null) throw new BusinessException(ErrorCode.FORBIDDEN);
        return Long.parseLong(identity.userId());
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_ERROR); }

    public record Cancel(@JsonDeserialize(using = PurchaseItems.StringId.class) String reason) { }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = {"shop.order.m3.enabled", "shop.order.m4.enabled"}, havingValue = "true")
    static class Security {
        @Bean
        InternalServiceEndpoint orderPaymentContextEndpoint(
                @Value("${shop.order.m4.payment-context.public-key}") Resource key) throws Exception {
            var scope = new ScopedServiceJwt.Scope("shop-pay", "shop-order", "payment:order:read");
            var verifier = ScopedServiceJwt.Verifier.forQueryBoundEndpoint(ScopedServiceJwt.publicKey(key), scope, Clock.systemUTC());
            return new InternalServiceEndpoint("GET", PathPatternParser.defaultInstance.parse(CONTEXT), verifier);
        }
    }
}
