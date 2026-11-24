package org.spring.shopuser.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.io.IOException;

public record AddressInput(
        @NotBlank @Size(max = 50) String receiver,
        @NotBlank @Pattern(regexp = "1[3-9][0-9]{9}") String phone,
        @NotBlank @Pattern(regexp = "[0-9]{6}") String regionCode,
        @NotBlank @Size(max = 200) String detail,
        @NotNull @JsonDeserialize(using = AddressInput.StrictBoolean.class) Boolean isDefault) {

    public AddressInput {
        if (receiver != null) receiver = receiver.strip();
        if (detail != null) detail = detail.strip();
    }

    public static class StrictBoolean extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_TRUE) && !parser.hasToken(JsonToken.VALUE_FALSE)) {
                return context.reportInputMismatch(Boolean.class, "isDefault must be a JSON boolean");
            }
            return parser.getBooleanValue();
        }
    }
}
