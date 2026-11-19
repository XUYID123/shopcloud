package org.spring.shopproduct.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 创建响应与既有 Sku 契约一致；ID 用字符串，库存尚未接入时明确返回 null。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SkuCreatedVO(String skuId, String productId, String skuCode, String specName,
                           long weightGram, long priceFen, boolean enabled,
                           Long estimatedAvailable, long version) { }
