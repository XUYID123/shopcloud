package org.spring.shopproduct.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.spring.shopproduct.dto.CrmCatalogFilters;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.sql.Types;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "shop.product.crm-preview.enabled", havingValue = "true")
public class CrmCatalogQueries {
    // Facets apply to a single SKU. A keyword match keeps the other facet-eligible SKUs of that SPU.
    private static final String MATCHED = """
            WITH matched AS (
                SELECT p.product_position, p.category_name, COUNT(p.sku_position) AS sku_count,
                       JSON_ARRAYAGG(p.sku_position) AS sku_positions
                FROM crm_catalog_snapshot s
                CROSS JOIN JSON_TABLE(s.payload, '$.products[*]' COLUMNS (
                    product_position FOR ORDINALITY,
                    category_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PATH '$.categoryName',
                    product_name VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci PATH '$.name',
                    brand VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci PATH '$.brand',
                    spu_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci PATH '$.spuCode',
                    NESTED PATH '$.skus[*]' COLUMNS (
                        sku_position FOR ORDINALITY,
                        sku_name VARCHAR(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci PATH '$.name',
                        sku_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci PATH '$.skuCode',
                        quota_category VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PATH '$.quotaCategory',
                        sku_brand VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PATH '$.brand',
                        product_type VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PATH '$.productType',
                        packaging VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PATH '$.packaging',
                        channel VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PATH '$.channel',
                        specification VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PATH '$.specification',
                        grade VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PATH '$.grade',
                        usage_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PATH '$.usage'
                    )
                )) p
                WHERE s.id = :snapshotId AND (:category IS NULL OR p.category_name = :category)
                  AND (:quotaCategory IS NULL OR p.quota_category = :quotaCategory)
                  AND (:brand IS NULL OR COALESCE(p.sku_brand, p.brand COLLATE utf8mb4_0900_bin) = :brand)
                  AND (:productType IS NULL OR p.product_type = :productType)
                  AND (:packaging IS NULL OR p.packaging = :packaging)
                  AND (:channel IS NULL OR p.channel = :channel)
                  AND (:specification IS NULL OR p.specification = :specification)
                  AND (:grade IS NULL OR p.grade = :grade)
                  AND (:usage IS NULL OR p.usage_name = :usage)
                GROUP BY p.product_position, p.category_name
                HAVING :pattern IS NULL OR MAX(
                    p.product_name LIKE :pattern ESCAPE '!' OR p.brand LIKE :pattern ESCAPE '!'
                    OR p.spu_code LIKE :pattern ESCAPE '!' OR p.sku_name LIKE :pattern ESCAPE '!'
                    OR p.sku_code LIKE :pattern ESCAPE '!'
                ) = 1
            )
            """;
    private static final String PRODUCT_SQL = """
            JSON_EXTRACT(s.payload, CONCAT('$.products[', p.product_position - 1, ']')) AS product, p.sku_positions
            """;

    private final NamedParameterJdbcOperations jdbc;
    private final ObjectMapper json;

    public CrmCatalogQueries(NamedParameterJdbcOperations jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Snapshot latest() {
        return jdbc.query("""
                SELECT id, payload->>'$.company' AS company, payload->>'$.capturedOn' AS captured_on,
                       payload->'$.facets' AS facets
                FROM crm_catalog_snapshot ORDER BY id DESC LIMIT 1
                """, Map.of(), (row, index) ->
                new Snapshot(row.getLong("id"), row.getString("company"), row.getString("captured_on"),
                        readJson(row.getString("facets"))))
                .stream().findFirst().orElse(null);
    }

    public List<String> categories(long snapshotId) {
        return jdbc.queryForList("""
                SELECT p.category_name
                FROM crm_catalog_snapshot s
                CROSS JOIN JSON_TABLE(s.payload, '$.products[*]' COLUMNS (
                    product_position FOR ORDINALITY,
                    category_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PATH '$.categoryName'
                )) p
                WHERE s.id = :snapshotId
                GROUP BY p.category_name
                ORDER BY MIN(p.product_position)
                """, Map.of("snapshotId", snapshotId), String.class);
    }

    public Counts count(long snapshotId, CrmCatalogFilters filters, String pattern) {
        return jdbc.queryForObject(MATCHED + """
                SELECT COUNT(*) AS total, COALESCE(SUM(sku_count), 0) AS sku_count FROM matched
                """, parameters(snapshotId, filters, pattern),
                (row, index) -> new Counts(row.getLong("total"), row.getLong("sku_count")));
    }

    public List<JsonNode> page(long snapshotId, CrmCatalogFilters filters, String pattern, int size, long offset) {
        return jdbc.query(MATCHED + "SELECT " + PRODUCT_SQL + """
                FROM (
                    SELECT product_position, sku_positions FROM matched ORDER BY product_position LIMIT :size OFFSET :offset
                ) p
                JOIN crm_catalog_snapshot s ON s.id = :snapshotId
                ORDER BY p.product_position
                """, parameters(snapshotId, filters, pattern).addValue("size", size).addValue("offset", offset),
                (row, index) -> readProduct(row.getString("product"), row.getString("sku_positions")));
    }

    public List<CategoryProduct> previews(long snapshotId, CrmCatalogFilters filters, String pattern, int size) {
        return jdbc.query(MATCHED + """
                , ranked AS (
                    SELECT product_position, category_name, sku_positions,
                           COUNT(*) OVER (PARTITION BY category_name) AS total,
                           SUM(sku_count) OVER (PARTITION BY category_name) AS sku_count,
                           ROW_NUMBER() OVER (PARTITION BY category_name ORDER BY product_position) AS category_position
                    FROM matched
                )
                SELECT p.category_name, p.total, p.sku_count,
                """ + PRODUCT_SQL + """
                FROM ranked p JOIN crm_catalog_snapshot s ON s.id = :snapshotId
                WHERE p.category_position <= :size
                ORDER BY p.product_position
                """, parameters(snapshotId, filters, pattern).addValue("size", size),
                (row, index) -> new CategoryProduct(row.getString("category_name"),
                        row.getLong("total"), row.getLong("sku_count"),
                        readProduct(row.getString("product"), row.getString("sku_positions"))));
    }

    private static MapSqlParameterSource parameters(long snapshotId, CrmCatalogFilters filters, String pattern) {
        var params = new MapSqlParameterSource("snapshotId", snapshotId).addValue("pattern", pattern, Types.VARCHAR);
        for (String key : CrmCatalogFilters.KEYS) params.addValue(key, filters.values().get(key), Types.VARCHAR);
        return params;
    }

    private JsonNode readProduct(String productJson, String positionsJson) throws SQLException {
        ObjectNode product = (ObjectNode) readJson(productJson);
        JsonNode skus = product.path("skus");
        JsonNode positions = readJson(positionsJson);
        if (positions.size() == skus.size()) return product;
        var keep = new BitSet(skus.size() + 1);
        positions.forEach(position -> keep.set(position.asInt()));
        var selected = json.createArrayNode();
        for (int index = 0; index < skus.size(); index++) {
            if (keep.get(index + 1)) selected.add(skus.get(index));
        }
        return product.set("skus", selected);
    }

    private JsonNode readJson(String document) throws SQLException {
        try {
            return document == null ? null : json.readTree(document);
        } catch (JsonProcessingException invalid) {
            throw new SQLException("Invalid CRM catalog product JSON", invalid);
        }
    }

    public record Snapshot(long id, String company, String capturedOn, JsonNode facets) { }

    public record Counts(long total, long skuCount) { }

    public record CategoryProduct(String category, long total, long skuCount, JsonNode product) { }
}
