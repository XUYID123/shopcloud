package org.spring.shopcommon.checkout;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;

import java.io.IOException;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/** Shared purchase-line contract for checkout and the authoritative product lookup. */
public record PurchaseItems(List<Item> items) {
    public record Item(@JsonDeserialize(using = StringId.class) String skuId, Integer quantity) { }

    public static SortedMap<Long, Integer> validated(List<Item> items) {
        if (items == null || items.isEmpty() || items.size() > 20) throw invalid();
        var quantities = new TreeMap<Long, Integer>();
        for (var item : items) {
            if (item == null || item.quantity() == null || item.quantity() < 1 || item.quantity() > 100) {
                throw invalid();
            }
            if (quantities.putIfAbsent(positiveId(item.skuId()), item.quantity()) != null) throw invalid();
        }
        return quantities;
    }

    public static long positiveId(String value) {
        if (value != null && value.matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException invalid) {
                throw invalid();
            }
        }
        throw invalid();
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR);
    }

    public static class StringId extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(String.class, "ID must be a JSON string");
            }
            return parser.getText();
        }
    }
}
