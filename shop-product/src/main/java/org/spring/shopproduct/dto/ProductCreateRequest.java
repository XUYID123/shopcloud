package org.spring.shopproduct.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.net.URI;

/** 新增 SPU 输入；不接收 ID、销售状态、版本、价格、库存或用户身份字段。 */
public record ProductCreateRequest(
        @NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String categoryId,
        @NotBlank @Size(max=100) String name,
        @NotBlank @Size(max=50) String brand,
        @NotNull GlutenType glutenType,
        @NotBlank @Size(max=200) String usageText,
        @NotBlank @Size(max=500) String ingredients,
        @NotBlank @Size(max=500) String storage,
        @NotNull @Min(1) @Max(1095) Integer shelfLifeDays,
        @NotBlank @Size(max=2048) String imageUrl) {

    /** 在校验和保存之前统一去掉首尾空白，防止“空白字符串”成为合法商品资料。 */
    public ProductCreateRequest {
        categoryId=trim(categoryId); name=trim(name); brand=trim(brand); usageText=trim(usageText);
        ingredients=trim(ingredients); storage=trim(storage); imageUrl=trim(imageUrl);
    }

    /** 第一版面粉的筋度枚举；非法枚举值由已有 Jackson 异常处理返回 400。 */
    public enum GlutenType { HIGH, MEDIUM, LOW }

    /** 只存图片地址，不下载；要求绝对 HTTPS URL、有效主机，禁止凭据和片段。 */
    @JsonIgnore
    @AssertTrue(message="图片地址必须是有效的 HTTPS 地址，且不能携带账号或片段")
    public boolean isImageUrlValid() {
        if (imageUrl == null || imageUrl.isBlank()) return true; // 必填由 NotBlank 提示。
        try {
            URI uri=URI.create(imageUrl);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null
                    && uri.getRawUserInfo()==null && uri.getRawFragment()==null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static String trim(String value) { return value==null ? null : value.strip(); }
}
