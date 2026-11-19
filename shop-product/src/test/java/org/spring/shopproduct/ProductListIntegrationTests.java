package org.spring.shopproduct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring.shopcommon.internalauth.InternalTestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProductListIntegrationTests {
    private static final String PATH = "/api/v1/products";
    private static final String TRACE = "product-list-test";
    private final String marker = "list" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private final List<Long> categoryIds = new ArrayList<>();
    private long categoryId;

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    @BeforeEach void createCategory() {
        categoryId = category("main", true);
    }

    @AfterEach void cleanFixtures() {
        for (long id : categoryIds) {
            jdbc.update("DELETE s FROM product_sku s JOIN product_spu p ON p.id=s.product_id WHERE p.category_id=?", id);
            jdbc.update("DELETE FROM product_spu WHERE category_id=?", id);
            jdbc.update("DELETE FROM category WHERE id=?", id);
        }
    }

    @Test void filtersVisibilityAndPaginatesWithStableMinimumPriceSorting() throws Exception {
        long cheap = product(categoryId, "A", "ON_SALE");
        long expensive = product(categoryId, "B", "ON_SALE");
        long tied = product(categoryId, "C", "ON_SALE");
        sku(cheap, "1", 700, true);
        sku(cheap, "2", 900, true);
        sku(cheap, "3", 100, false);
        sku(expensive, "1", 1200, true);
        sku(tied, "1", 700, true);
        sku(product(categoryId, "hidden", "OFF_SHELF"), "1", 1, true);
        sku(product(categoryId, "disabledsku", "ON_SALE"), "1", 1, false);
        product(categoryId, "nosku", "ON_SALE");
        sku(product(category("disabled", false), "disabledcat", "ON_SALE"), "1", 1, true);
        sku(product(category("other", true), "othercat", "ON_SALE"), "1", 1, true);

        var defaults = get("?categoryId=" + categoryId, true, 200, "OK");
        assertThat(defaults.path("page").asLong()).isEqualTo(1);
        assertThat(defaults.path("size").asInt()).isEqualTo(20);
        assertThat(defaults.path("total").asLong()).isEqualTo(3);
        assertThat(ids(defaults)).containsExactly(tied, expensive, cheap);

        var asc = get("?categoryId=" + categoryId + "&page=1&size=2&sort=PRICE_ASC", true, 200, "OK");
        assertThat(ids(asc)).containsExactly(tied, cheap);
        var card = asc.path("items").get(1);
        assertThat(card.size()).isEqualTo(5);
        assertThat(card.path("productId").isTextual()).isTrue();
        assertThat(card.path("minPriceFen").asLong()).isEqualTo(700);
        assertThat(card.path("status").asText()).isEqualTo("ON_SALE");
        assertThat(ids(get("?categoryId=" + categoryId + "&page=2&size=2&sort=PRICE_ASC", true, 200, "OK")))
                .containsExactly(expensive);
        assertThat(ids(get("?categoryId=" + categoryId + "&sort=PRICE_DESC", true, 200, "OK")))
                .containsExactly(expensive, tied, cheap);

        var beyond = get("?categoryId=" + categoryId + "&size=50&page=" + Long.MAX_VALUE, true, 200, "OK");
        assertThat(beyond.path("items").isArray()).isTrue();
        assertThat(ids(beyond)).isEmpty();
        assertThat(beyond.path("total").asLong()).isEqualTo(3);
    }

    @Test void matchesNameAndBrandLiterallyAndReturnsEmptyForUnknownCategory() throws Exception {
        long product = product(categoryId, "name%_suffix", "ON_SALE");
        sku(product, "1", 500, true);
        sku(product(categoryId, "ordinary", "ON_SALE"), "1", 900, true);
        sku(product(category("disabled", false), "hidden", "ON_SALE"), "1", 1, true);

        assertThat(ids(get("?categoryId=" + categoryId + "&keyword=%25%5F", true, 200, "OK"))).containsExactly(product);
        var byBrand = get("?keyword=" + marker, true, 200, "OK");
        assertThat(byBrand.path("total").asLong()).isEqualTo(2);
        assertThat(ids(get("?categoryId=" + categoryId + "&keyword=" + marker + "name", true, 200, "OK")))
                .containsExactly(product);
        var missing = get("?categoryId=" + Long.MAX_VALUE, true, 200, "OK");
        assertThat(ids(missing)).isEmpty();
        assertThat(missing.path("total").asLong()).isZero();
    }

    @Test void validatesParametersAndKeepsInternalJwtBoundary() throws Exception {
        for (String query : List.of("?page=0", "?page=9223372036854775808", "?size=51",
                "?categoryId=abc", "?sort=price_asc", "?keyword=" + "x".repeat(51))) {
            get(query, true, 400, "VALIDATION_ERROR");
        }
        get("", false, 401, "INVALID_INTERNAL_TOKEN");
    }

    private JsonNode get(String query, boolean signed, int status, String code) throws Exception {
        var headers = new HttpHeaders();
        headers.set("X-Trace-Id", TRACE);
        // 内部匿名 JWT 绑定原始查询串和路径，模拟网关转发。
        if (signed) {
            String rawQuery = query.isEmpty() ? "" : query.substring(1);
            headers.set("X-Internal-Token", InternalTestTokens.tokenForQuery(
                    "shop-product", null, "anonymous", "GET", PATH, rawQuery, Instant.now()));
        }
        URI uri = URI.create("http://localhost:" + port + PATH + query);
        var response = http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        var body = json.readTree(response.getBody());
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("traceId").asText()).isEqualTo(TRACE);
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo(TRACE);
        return body.path("data");
    }

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("productId").asLong()));
        return ids;
    }

    private long category(String suffix, boolean enabled) {
        String name = marker + suffix;
        jdbc.update("INSERT INTO category(name,sort,enabled) VALUES (?,0,?)", name, enabled);
        long id = jdbc.queryForObject("SELECT id FROM category WHERE name=?", Long.class, name);
        categoryIds.add(id);
        return id;
    }

    private long product(long category, String suffix, String status) {
        String name = marker + suffix;
        jdbc.update("""
                INSERT INTO product_spu(category_id,name,brand,gluten_type,usage_text,ingredients,storage,
                    shelf_life_days,image_url,status)
                VALUES (?,?,?,'HIGH','面包','小麦','干燥',180,'https://example.com/a.jpg',?)
                """, category, name, marker, status);
        return jdbc.queryForObject("SELECT id FROM product_spu WHERE category_id=? AND name=?", Long.class, category, name);
    }

    private void sku(long product, String suffix, int price, boolean enabled) {
        jdbc.update("""
                INSERT INTO product_sku(product_id,sku_code,spec_name,weight_gram,price_fen,enabled)
                VALUES (?,?,'5kg',5000,?,?)
                """, product, marker + product + "-" + suffix, price, enabled);
    }
}
