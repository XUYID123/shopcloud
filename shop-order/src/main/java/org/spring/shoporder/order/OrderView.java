package org.spring.shoporder.order;

import org.spring.shoporder.checkout.CheckoutQuote;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record OrderView(String orderNo, String orderStatus, String inventoryStatus, String paymentStatus,
                        String currency, long goodsAmountFen, long freightAmountFen, long payableAmountFen,
                        long totalWeightGram, List<CheckoutQuote.Line> items, Address address,
                        OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime paidAt,
                        String paymentNo, Shipment shipment, String failureCode, String exceptionCode) {
    public record Address(String receiver, String phone, String regionCode, String detail) { }
    public record Shipment(String carrierCode, String trackingNo, OffsetDateTime shippedAt,
                           List<BatchAllocation> allocations) { }
    public record BatchAllocation(String skuId, String batchId, String batchNo, int quantity,
                                  LocalDate expiresOn, String qualityStatus) { }
}
