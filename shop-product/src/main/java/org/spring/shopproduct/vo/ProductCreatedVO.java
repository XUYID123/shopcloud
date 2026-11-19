package org.spring.shopproduct.vo;

import java.util.List;

/** 复用 ProductDetail：创建时规格为空，后台返回全部规格，公开详情只返回启用规格。 */
public record ProductCreatedVO(String productId, String categoryId, String name, String brand,
        String glutenType, String usageText, String ingredients, String storage, int shelfLifeDays,
        String imageUrl, String status, long version, List<SkuCreatedVO> skus) { }
