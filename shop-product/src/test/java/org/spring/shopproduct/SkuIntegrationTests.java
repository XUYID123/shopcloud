package org.spring.shopproduct;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.spring.shopcommon.internalauth.InternalTestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/** 真实 HTTP/MySQL 验证 SKU 写入、唯一索引竞争、参数边界和内部身份保护。 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
class SkuIntegrationTests {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final String marker="sku_"+UUID.randomUUID().toString().replace("-", "");
    private long categoryId;
    private long productId;

    /** 每个测试使用独立的分类和下架商品，避免依赖已有开发数据。 */
    @BeforeEach void seed() {
        jdbc.update("INSERT INTO category(name,sort,enabled) VALUES (?,0,1)",marker);
        categoryId=jdbc.queryForObject("SELECT id FROM category WHERE name=?",Long.class,marker);
        jdbc.update("INSERT INTO product_spu(category_id,name,brand,gluten_type,usage_text,ingredients,storage,shelf_life_days,image_url) VALUES (?,?,'麦香','HIGH','面包','小麦','干燥',180,'https://example.com/a.jpg')",categoryId,marker);
        productId=jdbc.queryForObject("SELECT id FROM product_spu WHERE category_id=?",Long.class,categoryId);
    }
    /** 严格按外键顺序清理本测试创建的数据。 */
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM cache_invalidation WHERE cache_key=?",
                org.spring.shopproduct.cache.ProductCacheKeys.detail(productId));
        jdbc.update("DELETE FROM product_sku WHERE product_id=?",productId);
        jdbc.update("DELETE FROM product_spu WHERE id=?",productId);
        jdbc.update("DELETE FROM category WHERE id=?",categoryId);
    }

    @Test void createsSkuWithExactUnitsDefaultsLocationAndNoInventedInventory() throws Exception {
        var input=input(); input.put("specName"," 5kg袋装 ");input.put("enabled",false);
        var response=post(input,headers()); check(response,201,"OK");
        var data=json.readTree(response.getBody()).path("data");
        assertThat(data.size()).isEqualTo(9);
        assertThat(data.path("skuId").isTextual()).isTrue();
        assertThat(data.path("productId").asText()).isEqualTo(Long.toString(productId));
        assertThat(data.path("specName").asText()).isEqualTo("5kg袋装");
        assertThat(data.path("weightGram").asLong()).isEqualTo(5000);
        assertThat(data.path("priceFen").asLong()).isEqualTo(2990);
        assertThat(data.path("enabled").asBoolean()).isFalse();
        assertThat(data.path("version").asLong()).isEqualTo(1);
        assertThat(data.has("estimatedAvailable")).isTrue();assertThat(data.path("estimatedAvailable").isNull()).isTrue();
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo(path()+"/"+data.path("skuId").asText());
        var row=jdbc.queryForMap("SELECT * FROM product_sku WHERE id=?",data.path("skuId").asLong());
        assertThat(((Number)row.get("price_fen")).longValue()).isEqualTo(2990);
        assertThat(row.get("created_at")).isNotNull();assertThat(row.get("updated_at")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM product_spu WHERE id=?",String.class,productId)).isEqualTo("OFF_SHELF");
        // 编码唯一性不区分大小写；不允许通过修改大小写绕过。
        input.put("skuCode",marker.toUpperCase(Locale.ROOT));check(post(input,headers()),409,"SKU_CODE_EXISTS");
    }

    @Test void missingProductReturns404WithoutInsert() throws Exception {
        var input=input();input.put("productId",Long.toString(Long.MAX_VALUE));check(post(input,headers()),404,"PRODUCT_NOT_FOUND");
        assertNoRows();
    }

    @Test void validatesAllFieldsAndRejectsServerFieldInjection() throws Exception {
        check(post(Map.of(),headers()),400,"VALIDATION_ERROR");
        Object[][] invalid={{"productId","0"},{"productId","9223372036854775808"},{"productId",123},
                {"skuCode"," "},{"skuCode","x".repeat(51)},{"skuCode","非法编码"},{"specName"," "},{"specName","x".repeat(101)},
                {"weightGram",99},{"weightGram",50001},{"weightGram",1.5},{"weightGram","5000"},
                {"priceFen",0},{"priceFen",10000001},{"priceFen",2.99},{"priceFen","2990"},
                {"enabled",null},{"enabled","true"},{"version",1},{"skuId","1"},{"estimatedAvailable",99},{"stock",99}};
        for(var pair:invalid){var input=input();input.put((String)pair[0],pair[1]);check(post(input,headers()),400,"VALIDATION_ERROR");}
        // 确认契约两端的闭区间值可写入，避免校验边界偏差。
        var lower=input();lower.put("weightGram",100);lower.put("priceFen",1);check(post(lower,headers()),201,"OK");
        var upper=input();upper.put("skuCode",marker+"_max");upper.put("weightGram",50000);upper.put("priceFen",10000000);check(post(upper,headers()),201,"OK");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_sku WHERE product_id=?",Integer.class,productId)).isEqualTo(2);
    }

    @Test void rejectsForgedExpiredWrongAudienceAndAnonymousTokens() throws Exception {
        var missing=new HttpHeaders();missing.set("X-User-Id","42");check(post(input(),missing),401,"INVALID_INTERNAL_TOKEN");
        for(String token:List.of("static-secret",InternalTestTokens.token("shop-user","42","user","POST",path()),
                InternalTestTokens.token("shop-product","42","user","POST",path(),Instant.now().minusSeconds(90)))) {
            var h=headers();h.set("X-Internal-Token",token);check(post(input(),h),401,"INVALID_INTERNAL_TOKEN");
        }
        var anonymous=headers();anonymous.set("X-Internal-Token",InternalTestTokens.token("shop-product",null,"anonymous","POST",path()));
        check(post(input(),anonymous),403,"FORBIDDEN");assertNoRows();
    }

    @Test void concurrentSameCodeHasExactlyOneWinner() throws Exception {
        // 同时发出 12 次相同编码写入，验证数据库唯一索引而不是 JVM 锁在保护业务。
        var pool=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> futures=new ArrayList<>();
            for(int i=0;i<12;i++)futures.add(pool.submit(()->{start.await();return post(input(),headers());}));
            start.countDown();int created=0,conflicts=0;
            for(var f:futures){var r=f.get(30,TimeUnit.SECONDS);if(r.getStatusCode().value()==201){check(r,201,"OK");created++;}
                else {check(r,409,"SKU_CODE_EXISTS");conflicts++;}}
            assertThat(created).isEqualTo(1);assertThat(conflicts).isEqualTo(11);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_sku WHERE product_id=?",Integer.class,productId)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test void publicDetailReturnsContractAndCurrentPrice() throws Exception {
        String skuPath = createSkuForPublicRead();
        var result = getPublicSku(skuPath);
        check(result, 200, "OK");
        var data = json.readTree(result.getBody()).path("data");
        assertThat(data.size()).isEqualTo(9);
        assertThat(data.path("skuId").asText()).isEqualTo(skuPath.substring(skuPath.lastIndexOf('/') + 1));
        assertThat(data.path("skuId").isTextual()).isTrue();
        assertThat(data.path("productId").asText()).isEqualTo(Long.toString(productId));
        assertThat(data.path("skuCode").asText()).isEqualTo(marker);
        assertThat(data.path("specName").asText()).isEqualTo("5kg袋装");
        assertThat(data.path("weightGram").asLong()).isEqualTo(5000);
        assertThat(data.path("priceFen").asLong()).isEqualTo(2990);
        assertThat(data.path("version").asLong()).isEqualTo(1);
        assertThat(data.path("enabled").asBoolean()).isTrue();
        assertThat(data.path("estimatedAvailable").isNull()).isTrue();

        jdbc.update("UPDATE product_sku SET price_fen=3200, version=version+1 WHERE product_id=?", productId);
        var latest = json.readTree(getPublicSku(skuPath).getBody()).path("data");
        assertThat(latest.path("priceFen").asLong()).isEqualTo(3200);
        assertThat(latest.path("version").asLong()).isEqualTo(2);
    }

    @Test void publicDetailHidesMissingDisabledOffShelfAndDisabledCategory() throws Exception {
        String skuPath = createSkuForPublicRead();
        assertSkuHidden("/api/v1/skus/" + Long.MAX_VALUE);
        jdbc.update("UPDATE product_sku SET enabled=0 WHERE product_id=?", productId);
        assertSkuHidden(skuPath);
        jdbc.update("UPDATE product_sku SET enabled=1 WHERE product_id=?", productId);
        jdbc.update("UPDATE product_spu SET status='OFF_SHELF' WHERE id=?", productId);
        assertSkuHidden(skuPath);
        jdbc.update("UPDATE product_spu SET status='ON_SALE' WHERE id=?", productId);
        jdbc.update("UPDATE category SET enabled=0 WHERE id=?", categoryId);
        assertSkuHidden(skuPath);
    }

    @Test void publicDetailValidatesIdAndRequiresGatewayCredential() throws Exception {
        for (String id : List.of("0", "-1", "01", "abc", "9223372036854775808")) {
            check(getPublicSku("/api/v1/skus/" + id), 400, "VALIDATION_ERROR");
        }
        String skuPath = createSkuForPublicRead();
        check(http.getForEntity(skuPath, String.class), 401, "INVALID_INTERNAL_TOKEN");
        var wrongAudience = publicSkuHeaders(skuPath, "shop-user", "GET");
        check(http.exchange(skuPath, HttpMethod.GET, new HttpEntity<>(wrongAudience), String.class),
                401, "INVALID_INTERNAL_TOKEN");
        var wrongMethod = publicSkuHeaders(skuPath, "shop-product", "POST");
        check(http.exchange(skuPath, HttpMethod.POST, new HttpEntity<>(wrongMethod), String.class),
                403, "FORBIDDEN");
    }

    private String createSkuForPublicRead() throws Exception {
        var response = post(input(), headers());
        check(response, 201, "OK");
        jdbc.update("UPDATE product_spu SET status='ON_SALE' WHERE id=?", productId);
        return "/api/v1/skus/" + json.readTree(response.getBody()).path("data").path("skuId").asText();
    }

    private ResponseEntity<String> getPublicSku(String skuPath) {
        return http.exchange(skuPath, HttpMethod.GET, new HttpEntity<>(publicSkuHeaders(skuPath, "shop-product", "GET")), String.class);
    }

    private HttpHeaders publicSkuHeaders(String skuPath, String audience, String method) {
        var headers = new HttpHeaders();
        headers.set("X-Trace-Id", "public-sku-test");
        headers.set("X-Internal-Token", InternalTestTokens.token(audience, null, "anonymous", method, skuPath));
        return headers;
    }

    private void assertSkuHidden(String skuPath) throws Exception {
        var result = getPublicSku(skuPath);
        check(result, 404, "SKU_NOT_FOUND");
        assertThat(json.readTree(result.getBody()).hasNonNull("data")).isFalse();
    }

    /** 构建合法的六字段输入；每个测试拥有唯一编码。 */
    private Map<String,Object> input(){var m=new HashMap<String,Object>();m.put("productId",Long.toString(productId));
        m.put("skuCode",marker);m.put("specName","5kg袋装");m.put("weightGram",5000);m.put("priceFen",2990);m.put("enabled",true);return m;}
    private static String path(){return "/api/v1/admin/skus";}
    /** 仅测试签名凭据，生产私钥不进入测试源码。 */
    private HttpHeaders headers(){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.set("X-Trace-Id","sku-create-test");
        h.set("X-Internal-Token",InternalTestTokens.token("shop-product","42","user","POST",path()));return h;}
    private ResponseEntity<String> post(Object input,HttpHeaders h){return http.postForEntity(path(),new HttpEntity<>(input,h),String.class);}
    private void assertNoRows(){assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_sku WHERE product_id=?",Integer.class,productId)).isZero();}
    /** 所有成功和失败响应的追踪 ID 均需与响应头一致。 */
    private void check(ResponseEntity<String> r,int status,String code)throws Exception{assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(status);
        var b=json.readTree(r.getBody());assertThat(b.path("code").asText()).isEqualTo(code);
        assertThat(b.path("traceId").asText()).isEqualTo(r.getHeaders().getFirst("X-Trace-Id"));}
}
