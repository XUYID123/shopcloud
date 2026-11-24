package org.spring.shoporder.cart;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CartItem(String skuId, int quantity, String productName, String specName,
                       Long priceFen, Integer weightGram, boolean availableForSale) { }
