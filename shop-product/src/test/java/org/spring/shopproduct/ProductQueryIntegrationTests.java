package org.spring.shopproduct;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.spring.shopcommon.internalauth.InternalTestTokens;
import org.spring.shopproduct.cache.ProductCacheKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 真实 HTTP/MySQL 验证商品公开可见性、规格过滤、统一响应和内部匿名凭据。 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
class ProductQueryIntegrationTests {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    private final String marker="query_"+UUID.randomUUID().toString().replace("-", "");
    private long categoryId,productId;

    /** 独立创建分类、已上架商品和三个规格，中间的规格禁用。 */
    @BeforeEach void seed() {
        jdbc.update("INSERT INTO category(name,sort,enabled) VALUES (?,0,1)",marker);
        categoryId=jdbc.queryForObject("SELECT id FROM category WHERE name=?",Long.class,marker);
        jdbc.update("INSERT INTO product_spu(category_id,name,brand,gluten_type,usage_text,ingredients,storage,shelf_life_days,image_url,status,version) VALUES (?,?,'麦香','HIGH','面包','小麦','干燥',180,'https://example.com/a.jpg','ON_SALE',2)",categoryId,marker);
        productId=jdbc.queryForObject("SELECT id FROM product_spu WHERE category_id=?",Long.class,categoryId);
        for(int i=0;i<3;i++)jdbc.update("INSERT INTO product_sku(product_id,sku_code,spec_name,weight_gram,price_fen,enabled) VALUES (?,?,'5kg',5000,2990,?)",productId,marker+i,i!=1);
    }
    /** 仅清理本用例数据，按外键顺序执行。 */
    @AfterEach void cleanup() {
        redis.delete(ProductCacheKeys.detail(productId));
        jdbc.update("DELETE FROM product_sku WHERE product_id=?",productId);
        jdbc.update("DELETE FROM product_spu WHERE id=?",productId);
        jdbc.update("DELETE FROM category WHERE id=?",categoryId);
    }

    @Test void anonymousInternalTokenCanReadExactContractWithOnlyEnabledSkus() throws Exception {
        var r=get(path(),headers(path()));check(r,200,"OK");var data=json.readTree(r.getBody()).path("data");
        assertThat(data.size()).isEqualTo(13);assertThat(data.path("productId").asText()).isEqualTo(Long.toString(productId));
        assertThat(data.path("categoryId").asText()).isEqualTo(Long.toString(categoryId));
        assertThat(data.path("name").asText()).isEqualTo(marker);assertThat(data.path("status").asText()).isEqualTo("ON_SALE");
        assertThat(data.path("version").asLong()).isEqualTo(2);assertThat(data.path("skus").size()).isEqualTo(2);
        var first=data.path("skus").get(0);var last=data.path("skus").get(1);
        assertThat(first.size()).isEqualTo(9);assertThat(first.path("skuId").isTextual()).isTrue();
        assertThat(first.path("skuCode").asText()).isEqualTo(marker+"0");assertThat(last.path("skuCode").asText()).isEqualTo(marker+"2");
        assertThat(first.path("enabled").asBoolean()).isTrue();assertThat(first.path("estimatedAvailable").isNull()).isTrue();
        assertThat(first.path("priceFen").asLong()).isEqualTo(2990);assertThat(first.path("weightGram").asLong()).isEqualTo(5000);
        // 查询不得修改商品版本或销售状态。
        assertThat(jdbc.queryForObject("SELECT version FROM product_spu WHERE id=?",Long.class,productId)).isEqualTo(2);
    }

    @Test void hiddenAndMissingProductsHaveSame404AndNoProductData() throws Exception {
        jdbc.update("UPDATE product_spu SET status='OFF_SHELF' WHERE id=?",productId);
        check(get(path(),headers(path())),404,"PRODUCT_NOT_FOUND");
        jdbc.update("UPDATE product_spu SET status='ON_SALE' WHERE id=?",productId);
        jdbc.update("UPDATE category SET enabled=0 WHERE id=?",categoryId);
        // SQL fixture bypasses the service and its outbox. Evict to exercise the DB category filter.
        redis.delete(ProductCacheKeys.detail(productId));
        check(get(path(),headers(path())),404,"PRODUCT_NOT_FOUND");
        var missing="/api/v1/products/"+Long.MAX_VALUE;check(get(missing,headers(missing)),404,"PRODUCT_NOT_FOUND");
    }

    @Test void noEnabledSkuReturnsEmptyArrayAndBadIdsReturn400() throws Exception {
        jdbc.update("UPDATE product_sku SET enabled=0 WHERE product_id=?",productId);
        var r=get(path(),headers(path()));check(r,200,"OK");
        assertThat(json.readTree(r.getBody()).path("data").path("skus").isArray()).isTrue();
        assertThat(json.readTree(r.getBody()).path("data").path("skus").size()).isZero();
        for(String id:List.of("0","-1","abc","01","9223372036854775808")){
            var p="/api/v1/products/"+id;check(get(p,headers(p)),400,"VALIDATION_ERROR");}
    }

    @Test void publicEndpointStillRejectsMissingForgedWrongAudienceOrWrongPathCredentials() throws Exception {
        var missing=new HttpHeaders();missing.set("X-User-Id","42");check(get(path(),missing),401,"INVALID_INTERNAL_TOKEN");
        for(String token:List.of("static-secret",InternalTestTokens.token("shop-user",null,"anonymous","GET",path()),
                InternalTestTokens.token("shop-product",null,"anonymous","GET",path()+"9"))){
            var h=headers(path());h.set("X-Internal-Token",token);check(get(path(),h),401,"INVALID_INTERNAL_TOKEN");}
    }

    private String path(){return "/api/v1/products/"+productId;}
    /** 使用测试私钥产生内部匿名 JWT，模拟网关；前端不负责提交此凭据。 */
    private HttpHeaders headers(String p){var h=new HttpHeaders();h.set("X-Trace-Id","product-detail-test");
        h.set("X-Internal-Token",InternalTestTokens.token("shop-product",null,"anonymous","GET",p));return h;}
    private ResponseEntity<String> get(String p,HttpHeaders h){return http.exchange(p,HttpMethod.GET,new HttpEntity<>(h),String.class);}
    /** 成功和失败均沿用统一响应，失败不能泄漏商品字段。 */
    private void check(ResponseEntity<String> r,int status,String code)throws Exception{assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(status);
        var b=json.readTree(r.getBody());assertThat(b.path("code").asText()).isEqualTo(code);
        assertThat(b.path("traceId").asText()).isEqualTo(r.getHeaders().getFirst("X-Trace-Id"));
        if(status!=200)assertThat(b.hasNonNull("data")).isFalse();}
}
