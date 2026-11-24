package org.spring.shopproduct.dto;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Query parameters use the same facet keys as the imported CRM catalog. */
public record CrmCatalogFilters(Map<String, String> values) {
    public static final List<String> KEYS = List.of("category", "quotaCategory", "brand", "productType",
            "packaging", "channel", "specification", "grade", "usage");

    public static CrmCatalogFilters parse(Map<String, String> parameters) {
        Map<String, String> values = new HashMap<>();
        for (String key : KEYS) {
            String value = parameters.get(key);
            if (value == null) continue;
            if (value.codePointCount(0, value.length()) > 255) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
            if (!value.isBlank()) values.put(key, value.strip());
        }
        return new CrmCatalogFilters(Map.copyOf(values));
    }
}
