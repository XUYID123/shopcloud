package org.spring.shopproduct.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/** SKU 创建和公开查询复用的契约。ID 使用字符串，库存尚未接入时返回 null。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SkuCreatedVO(String skuId, String productId, String skuCode, String specName,
                           long weightGram, long priceFen, boolean enabled,
                           Long estimatedAvailable, long version) { }
