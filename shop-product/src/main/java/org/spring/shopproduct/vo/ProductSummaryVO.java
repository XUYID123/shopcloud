package org.spring.shopproduct.vo;

/** 商品列表卡片，最低价以人民币分表示。 */
public record ProductSummaryVO(String productId, String name, String imageUrl, long minPriceFen, String status) { }
