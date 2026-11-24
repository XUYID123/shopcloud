package org.spring.shopproduct.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.logging.BizLog;
import org.spring.shopproduct.dto.CrmCatalogFilters;
import org.spring.shopproduct.dto.ProductListQuery;
import org.spring.shopproduct.mapper.CrmCatalogQueries;
import org.spring.shopproduct.mapper.CrmCatalogQueries.Snapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Service
@ConditionalOnProperty(name = "shop.product.crm-preview.enabled", havingValue = "true")
public class CrmCatalogPreviewService {
    private static final int PREVIEW_SIZE = 4;
    private final CrmCatalogQueries catalog;
    private final TransactionTemplate readTransaction;

    public CrmCatalogPreviewService(CrmCatalogQueries catalog, PlatformTransactionManager transactionManager) {
        this.catalog = catalog;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @BizLog(action = "CRM_CATALOG_PAGE")
    public Page page(CrmCatalogFilters filters, String keyword, String page, String size) {
        ProductListQuery paging = ProductListQuery.parse(null, null, null, page, size == null ? "12" : size);
        String pattern = keywordPattern(keyword);
        return readLatest(snapshot -> {
            CrmCatalogQueries.Counts counts = catalog.count(snapshot.id(), filters, pattern);
            List<JsonNode> items = List.of();
            if (counts.total() > 0 && paging.page() <= (counts.total() - 1) / paging.size() + 1) {
                long offset = (paging.page() - 1) * paging.size();
                items = catalog.page(snapshot.id(), filters, pattern, paging.size(), offset);
            }
            return new Page(snapshot.company(), snapshot.capturedOn(), items, paging.page(), paging.size(),
                    counts.total(), counts.skuCount());
        });
    }

    @BizLog(action = "CRM_CATALOG_OVERVIEW")
    public Overview overview(CrmCatalogFilters filters, String keyword) {
        String pattern = keywordPattern(keyword);
        return readLatest(snapshot -> {
            List<String> categories = catalog.categories(snapshot.id());
            Map<String, CategoryGroup> groupsByName = new HashMap<>();
            for (CrmCatalogQueries.CategoryProduct item : catalog.previews(snapshot.id(), filters, pattern, PREVIEW_SIZE)) {
                groupsByName.computeIfAbsent(item.category(),
                        name -> new CategoryGroup(name, item.total(), item.skuCount(), new ArrayList<>()))
                        .items().add(item.product());
            }
            List<CategoryGroup> groups = categories.stream().map(groupsByName::get).filter(Objects::nonNull).toList();
            return new Overview(snapshot.company(), snapshot.capturedOn(), categories, snapshot.facets(), groups,
                    groups.stream().mapToLong(CategoryGroup::total).sum(),
                    groups.stream().mapToLong(CategoryGroup::skuCount).sum());
        });
    }

    private <T> T readLatest(Function<Snapshot, T> query) {
        try {
            return readTransaction.execute(transaction -> {
                Snapshot snapshot = catalog.latest();
                return snapshot == null ? null : query.apply(snapshot);
            });
        } catch (CannotCreateTransactionException | TransientDataAccessException |
                 DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    private static String keywordPattern(String keyword) {
        String value = clean(keyword, 100);
        return value == null ? null : "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String clean(String raw, int maxLength) {
        if (raw == null) return null;
        if (raw.codePointCount(0, raw.length()) > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        return raw.isBlank() ? null : raw.strip();
    }

    public record Page(String company, String capturedOn, List<JsonNode> items, long page, int size,
                       long total, long skuCount) { }

    public record CategoryGroup(String name, long total, long skuCount, List<JsonNode> items) { }

    public record Overview(String company, String capturedOn, List<String> categories, JsonNode facets,
                           List<CategoryGroup> groups, long total, long skuCount) { }
}
