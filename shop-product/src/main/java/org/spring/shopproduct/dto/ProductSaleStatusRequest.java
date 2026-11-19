package org.spring.shopproduct.dto;

import jakarta.validation.constraints.*;

/** 销售状态独立输入；不允许同时修改商品资料、SKU 或库存。 */
public record ProductSaleStatusRequest(@NotNull SaleStatus status,
        @NotNull @Min(1) @Max(9223372036854775806L) Long expectedVersion) {
    /** Jackson 只接受约定的两个枚举字符串，非法值统一返回 400。 */
    public enum SaleStatus { ON_SALE, OFF_SHELF }
}
