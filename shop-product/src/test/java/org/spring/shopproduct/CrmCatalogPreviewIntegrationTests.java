package org.spring.shopproduct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spring.shopcommon.internalauth.InternalTestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shop.product.crm-preview.enabled=true")
@ActiveProfiles("test")
class CrmCatalogPreviewIntegrationTests {
    private static final String TOKEN = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    private static final String LIST = "/api/v1/products/crm-catalog";
    private static final String OVERVIEW = LIST + "/overview";

    @DynamicPropertySource
    static void crmPreviewProperties(DynamicPropertyRegistry registry) {
        registry.add("shop.product.crm-preview.token", () -> TOKEN);
    }

    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;
    private final List<String> inserted = new ArrayList<>();

    @BeforeEach void seed() {
        var old = json.createArrayNode();
        product(old, "Old", "old", "old", "SKU-OLD");
        snapshot(old);
        var latest = json.createArrayNode();
        product(latest, "Flour", "F1", "Flour 100%", "SKU-ONLY", "SKU-SECOND");
        ((ObjectNode) latest.get(0).at("/skus/0")).put("brand", "Sample").put("quotaCategory", "Quota-A")
                .put("productType", "Type-A").put("packaging", "Bag").put("channel", "Retail")
                .put("specification", "25kg").put("grade", "Six").put("usage", "Bread");
        ((ObjectNode) latest.get(0).at("/skus/1")).put("packaging", "Box").put("specification", "5kg");
        for (int i = 2; i <= 6; i++) {
            product(latest, "Flour", "F" + i, "Flour " + i, "SKU-" + i);
        }
        product(latest, "Noodles", "N1", "Noodles", "SKU-N");
        snapshot(latest);
    }

    @AfterEach void cleanup() {
        inserted.forEach(hash -> jdbc.update("DELETE FROM crm_catalog_snapshot WHERE payload_sha256 = UNHEX(?)", hash));
    }

    @Test void pagesTheLatestSnapshotAndCountsTheCategory() throws Exception {
        JsonNode first = get(LIST, "category=Flour&page=1&size=2", TOKEN, 200, "OK");
        JsonNode second = get(LIST, "category=Flour&page=2&size=2", TOKEN, 200, "OK");
        assertThat(codes(first.path("items"))).containsExactly("F1", "F2");
        assertThat(codes(second.path("items"))).containsExactly("F3", "F4");
        assertThat(second.path("total").asLong()).isEqualTo(6);
        assertThat(second.path("skuCount").asLong()).isEqualTo(7);
        assertThat(second.path("page").asLong()).isEqualTo(2);
        assertThat(second.path("size").asInt()).isEqualTo(2);
        JsonNode beyond = get(LIST, "category=Flour&page=9223372036854775807&size=2", TOKEN, 200, "OK");
        assertThat(beyond.path("items").isEmpty()).isTrue();
        assertThat(beyond.path("total").asLong()).isEqualTo(6);
        JsonNode all = get(LIST, "", TOKEN, 200, "OK");
        assertThat(codes(all.path("items"))).containsExactly("F1", "F2", "F3", "F4", "F5", "F6", "N1");
        assertThat(all.path("size").asInt()).isEqualTo(12);
    }

    @Test void searchesSkuNamesAndCodesAndReturnsBoundedPreviews() throws Exception {
        JsonNode found = get(LIST, "keyword=sku-only", TOKEN, 200, "OK");
        assertThat(codes(found.path("items"))).containsExactly("F1");
        assertThat(found.path("skuCount").asLong()).isEqualTo(2);
        assertThat(codes(get(LIST, "keyword=Flour%20100%25%20SKU-ONLY", TOKEN, 200, "OK").path("items")))
                .containsExactly("F1");
        assertThat(codes(get(LIST, "keyword=%25", TOKEN, 200, "OK").path("items"))).containsExactly("F1");
        JsonNode overview = get(OVERVIEW, "", TOKEN, 200, "OK");
        assertThat(overview.path("groups").size()).isEqualTo(2);
        assertThat(overview.path("total").asLong()).isEqualTo(7);
        assertThat(overview.path("skuCount").asLong()).isEqualTo(8);
        assertThat(overview.path("groups").get(0).path("total").asLong()).isEqualTo(6);
        assertThat(codes(overview.path("groups").get(0).path("items"))).containsExactly("F1", "F2", "F3", "F4");
        JsonNode filtered = get(OVERVIEW, "keyword=noodles", TOKEN, 200, "OK");
        assertThat(filtered.path("categories").size()).isEqualTo(2);
        assertThat(filtered.path("groups").size()).isEqualTo(1);
        assertThat(filtered.path("groups").get(0).path("name").asText()).isEqualTo("Noodles");
    }

    @Test void rejectsInvalidPaginationAndRequiresPreviewToken() throws Exception {
        get(LIST, "page=0", TOKEN, 400, "VALIDATION_ERROR");
        get(LIST, "size=51", TOKEN, 400, "VALIDATION_ERROR");
        get(LIST, "keyword=" + "x".repeat(101), TOKEN, 400, "VALIDATION_ERROR");
        get(LIST, "brand=" + "x".repeat(256), TOKEN, 400, "VALIDATION_ERROR");
        get(LIST, "page=2", null, 403, "FORBIDDEN");
        get(OVERVIEW, "keyword=flour", "invalid", 403, "FORBIDDEN");
    }

    @Test void appliesAllFacetsToTheSameSkuBeforeGroupingAndPaginating() throws Exception {
        String filters = "category=Flour&quotaCategory=Quota-A&brand=Sample&productType=Type-A"
                + "&packaging=Bag&channel=Retail&specification=25kg&grade=Six&usage=Bread";
        JsonNode selected = get(LIST, filters, TOKEN, 200, "OK");
        assertThat(codes(selected.path("items"))).containsExactly("F1");
        assertThat(selected.path("skuCount").asLong()).isEqualTo(1);
        JsonNode skus = selected.at("/items/0/skus");
        assertThat(skus.size()).isEqualTo(1);
        assertThat(skus.get(0).path("skuCode").asText()).isEqualTo("SKU-ONLY");
        assertThat(get(LIST, "packaging=Box&specification=25kg", TOKEN, 200, "OK").path("total").asLong())
                .isZero();
        assertThat(get(LIST, filters + "&keyword=SKU-SECOND", TOKEN, 200, "OK").path("total").asLong())
                .isZero();
        JsonNode overview = get(OVERVIEW, "quotaCategory=Quota-A&grade=Six", TOKEN, 200, "OK");
        assertThat(overview.path("total").asLong()).isEqualTo(1);
        assertThat(overview.path("skuCount").asLong()).isEqualTo(1);
        assertThat(codes(overview.at("/groups/0/items"))).containsExactly("F1");
    }

    private JsonNode get(String path, String query, String token, int status, String code) throws Exception {
        var headers = new HttpHeaders();
        headers.set("X-Internal-Token", InternalTestTokens.tokenForQuery(
                "shop-product", null, "anonymous", "GET", path, query, Instant.now()));
        if (token != null) headers.set("X-Crm-Preview-Token", token);
        URI url = URI.create("http://localhost:" + port + path + (query.isEmpty() ? "" : "?" + query));
        var response = http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        JsonNode data = json.readTree(response.getBody());
        assertThat(data.path("code").asText()).isEqualTo(code);
        return data.path("data");
    }

    private void snapshot(ArrayNode products) {
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        var document = json.createObjectNode().put("company", "Test catalog").put("capturedOn", "2026-11-20");
        document.set("products", products);
        jdbc.update("INSERT INTO crm_catalog_snapshot (payload_sha256, payload) VALUES (UNHEX(?), ?)",
                hash, document.toString());
        inserted.add(hash);
    }

    private static void product(ArrayNode products, String category, String code, String name, String... skuCodes) {
        var skus = products.addObject().put("categoryName", category).put("spuCode", code)
                .put("name", name).put("brand", "Sample").putArray("skus");
        for (String skuCode : skuCodes) {
            skus.addObject().put("skuCode", skuCode).put("name", name + " " + skuCode);
        }
    }

    private static List<String> codes(JsonNode items) {
        var codes = new ArrayList<String>();
        items.forEach(item -> codes.add(item.path("spuCode").asText()));
        return codes;
    }
}
