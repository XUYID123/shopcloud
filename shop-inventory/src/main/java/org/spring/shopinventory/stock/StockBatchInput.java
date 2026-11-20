package org.spring.shopinventory.stock;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.io.IOException;

/** 仅接收入库所需的业务字段，数量单位为袋。日期在服务层严格解析并核对保质期。 */
public record StockBatchInput(
        @JsonDeserialize(using = TextOnly.class) @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String skuId,
        @NotBlank @Pattern(regexp = "WH-SH-01") String warehouseId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[^\\p{Cc}]+") String batchNo,
        @NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String manufacturedOn,
        @NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") String expiresOn,
        @NotNull @Min(1) @Max(1_000_000) Integer quantity) {
    public StockBatchInput {
        if (batchNo != null) batchNo = batchNo.strip();
    }

    /** Jackson 通用标量开关无法阻止数字转 String，单独固定对外 ID 的 JSON 类型。 */
    public static final class TextOnly extends StdScalarDeserializer<String> {
        public TextOnly() { super(String.class); }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (String) context.handleUnexpectedToken(String.class, parser);
            }
            return parser.getText();
        }
    }
}
