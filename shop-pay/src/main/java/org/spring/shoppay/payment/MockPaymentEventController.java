package org.spring.shoppay.payment;

import jakarta.servlet.http.HttpServletRequest;
import org.spring.shopcommon.result.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("(dev | test) & !prod & !production")
@ConditionalOnProperty(name = "shop.pay.enabled", havingValue = "true")
public class MockPaymentEventController {
    static final String PATH = "/api/v1/test/payment-sessions/{paymentNo}/payment-events";
    private final PaymentService payments;

    MockPaymentEventController(PaymentService payments) { this.payments = payments; }

    @PostMapping(path = PATH, consumes = "application/json")
    public ResponseEntity<Result<PaymentViews.Event>> create(HttpServletRequest request,
            @PathVariable("paymentNo") String paymentNo, @RequestBody Input input) {
        PaymentController.owner(request);
        PaymentController.key(request);
        var result = payments.createMockEvent(paymentNo, input.type());
        return PaymentController.response(result.created() ? 201 : 200, result.data(),
                PATH.replace("{paymentNo}", paymentNo) + "/" + result.data().eventId());
    }

    @GetMapping(PATH + "/{eventId}")
    public ResponseEntity<Result<PaymentViews.Event>> find(HttpServletRequest request, @PathVariable("paymentNo") String paymentNo,
            @PathVariable("eventId") String eventId) {
        PaymentController.owner(request);
        return PaymentController.response(200, payments.mockEvent(paymentNo, eventId), null);
    }

    public record Input(String type) { }
}
