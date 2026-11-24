package org.spring.shopcommon.checkout;

import java.util.List;

public record ProductQuote(List<Line> lines) {
    public record Line(String skuId, String productId, String productName, String specName,
                       Long weightGram, Long unitPriceFen, Long productVersion, Long skuVersion) { }
}
