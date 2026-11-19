package org.spring.shopproduct.dto;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;

/** 校验公开列表参数，进入 Mapper 后只使用已解析的值和固定排序选项。 */
public record ProductListQuery(Long categoryId, String keyword, Sort sort, long page, int size) {
    public enum Sort { DEFAULT, PRICE_ASC, PRICE_DESC }

    public static ProductListQuery parse(String categoryId, String keyword, String sort, String page, String size) {
        Long category = categoryId == null ? null : positiveLong(categoryId);
        long pageNumber = page == null ? 1 : positiveLong(page);
        long pageSize = size == null ? 20 : positiveLong(size);
        if (pageSize > 50 || (keyword != null && keyword.codePointCount(0, keyword.length()) > 50)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        String search = keyword == null ? null : keyword.strip();
        if (search != null && search.isEmpty()) {
            search = null;
        }
        Sort sortOrder;
        try {
            sortOrder = sort == null ? Sort.DEFAULT : Sort.valueOf(sort);
        } catch (IllegalArgumentException invalidSort) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return new ProductListQuery(category, search, sortOrder, pageNumber, (int) pageSize);
    }

    private static long positiveLong(String value) {
        if (value.matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException overflow) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
}
