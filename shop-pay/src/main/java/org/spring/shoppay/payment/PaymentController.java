package org.spring.shoppay.payment;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.servlet.http.HttpServletRequest;
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

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.Collections;

@RestController
@ConditionalOnProperty(name = "shop.pay.enabled", havingValue = "true")
public class PaymentController {
    private static final String SESSIONS = "/api/v1/payment-sessions";
    private static final String RECONCILIATION = "/internal/v1/payments/orders/{orderNo}";
    private final PaymentService payments;

    PaymentController(PaymentService payments) { this.payments = payments; }

    @PostMapping(path = SESSIONS, consumes = "application/json")
    public ResponseEntity<Result<PaymentViews.Session>> create(HttpServletRequest request, @RequestBody Create input) {
        var result = payments.create(owner(request), input.orderNo(), input.provider(), key(request));
        return response(result.created() ? 201 : 200, result.data(), SESSIONS + "/" + result.data().paymentNo());
    }

    @GetMapping(SESSIONS + "/{paymentNo}")
    public ResponseEntity<Result<PaymentViews.Session>> find(HttpServletRequest request, @PathVariable("paymentNo") String number) {
        return response(200, payments.find(owner(request), number), null);
    }

    @PostMapping(path = "/callbacks/payments/{provider}", consumes = "application/json")
    public ResponseEntity<PaymentViews.CallbackAck> callback(HttpServletRequest request,
            @PathVariable("provider") String provider) throws IOException {
        if (!"MOCK".equals(provider)) throw new BusinessException(ErrorCode.NOT_FOUND);
        byte[] body = request.getInputStream().readNBytes(MockCallbackCodec.MAX_BYTES + 1);
        payments.callback(body, single(request, "X-Mock-Timestamp"), single(request, "X-Mock-Nonce"),
                single(request, "X-Mock-Signature"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PaymentViews.CallbackAck("SUCCESS"));
    }

    @GetMapping(RECONCILIATION)
    public ResponseEntity<Result<PaymentViews.Control>> reconcile(HttpServletRequest request,
            @PathVariable("orderNo") String orderNo) {
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"service".equals(identity.kind())) throw new BusinessException(ErrorCode.FORBIDDEN);
        return response(200, payments.reconciliation(orderNo), null);
    }

    static long owner(HttpServletRequest request) {
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"user".equals(identity.kind()) || identity.userId() == null) throw new BusinessException(ErrorCode.FORBIDDEN);
        return Long.parseLong(identity.userId());
    }

    static String key(HttpServletRequest request) {
        String value = single(request, "Idempotency-Key");
        if (value == null || !value.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return value;
    }

    private static String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.get(0) : null;
    }

    static <T> ResponseEntity<Result<T>> response(int status, T data, String location) {
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (location != null) response.location(URI.create(location));
        return response.body(Result.success(data));
    }

    public record Create(@JsonDeserialize(using = PurchaseItems.StringId.class) String orderNo,
                         @JsonDeserialize(using = PurchaseItems.StringId.class) String provider) { }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "shop.pay.enabled", havingValue = "true")
    static class Security {
        @Bean
        InternalServiceEndpoint paymentReconciliationEndpoint(
                @Value("${shop.pay.reconciliation.public-key}") Resource key) throws Exception {
            var scope = new ScopedServiceJwt.Scope("shop-order", "shop-pay", "order:payment:read");
            return new InternalServiceEndpoint("GET", PathPatternParser.defaultInstance.parse(RECONCILIATION),
                    new ScopedServiceJwt.Verifier(ScopedServiceJwt.publicKey(key), scope, Clock.systemUTC()));
        }
    }
}
