package org.spring.shoppay.payment;

import java.time.OffsetDateTime;

public final class PaymentViews {
    private PaymentViews() { }

    public record Session(String paymentNo, String orderNo, String provider, String status, long amountFen,
                          String currency, OffsetDateTime expiresAt, ClientParams clientParams) { }
    public record ClientParams(String mockPaymentNo) { }
    public record Event(String eventId, String paymentNo, String type, OffsetDateTime createdAt) { }
    public record Control(String orderNo, String state, String paymentStatus, String paymentNo, long amountFen,
                          String currency, boolean closeRequested, String refundStatus) { }
    public record CallbackAck(String code) { }
}
