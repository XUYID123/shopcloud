package org.spring.shoporder.checkout;

import java.time.OffsetDateTime;
import java.util.List;

public record CheckoutQuote(String quoteId, String quoteVersion, String currency, List<Line> items,
                            long goodsAmountFen, long freightAmountFen, long payableAmountFen, long totalWeightGram,
                            String freightRuleVersion, OffsetDateTime expiresAt) {
    public record Line(String skuId, String productName, String specName, long unitPriceFen, int quantity,
                       long weightGram, long lineAmountFen) { }
}
