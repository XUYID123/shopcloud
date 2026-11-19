package org.spring.shopproduct.dto;

import jakarta.validation.constraints.*;

/** 创建 SKU 的六个输入字段；拒绝客户端提交主键、版本、库存和身份信息。 */
public record SkuCreateRequest(
        @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String productId,
        @NotBlank @Size(max=50) @Pattern(regexp="[A-Za-z0-9_-]+") String skuCode,
        @NotBlank @Size(max=100) String specName,
        @NotNull @Min(100) @Max(50000) Long weightGram,
        @NotNull @Min(1) @Max(10000000) Long priceFen,
        @NotNull Boolean enabled) {
    /** 校验前去除首尾空白；编码保留输入大小写，但数据库唯一性不区分大小写。 */
    public SkuCreateRequest {
        productId=strip(productId); skuCode=strip(skuCode); specName=strip(specName);
    }
    /** 允许缺失字段进入 Bean Validation 统一返回 400。 */
    private static String strip(String value) { return value==null ? null : value.strip(); }
}
