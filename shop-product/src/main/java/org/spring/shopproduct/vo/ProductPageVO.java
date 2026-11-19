package org.spring.shopproduct.vo;

import java.util.List;

/** 对应既有 ProductPage 协议，total 表示应用筛选后的商品总数。 */
public record ProductPageVO(List<ProductSummaryVO> items, long page, int size, long total) { }
