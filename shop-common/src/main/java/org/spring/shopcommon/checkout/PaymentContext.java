package org.spring.shopcommon.checkout;

import java.time.OffsetDateTime;

public record PaymentContext(String orderNo, String userId, long amountFen, String currency,
                             String orderStatus, OffsetDateTime expiresAt) { }
