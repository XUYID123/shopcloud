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
import static org.assertj.core.api.Assertions.*;

/** 真实 HTTP/MySQL 验证上架条件、下架可用性、版本竞争和内部 JWT 保护。 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
class ProductSaleIntegrationTests {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final String marker="sale_"+UUID.randomUUID().toString().replace("-", "");
    private long categoryId,productId;

    /** 独立创建测试分类和下架 SPU；每个用例自行决定是否创建规格。 */
    @BeforeEach void seed() {
        jdbc.update("INSERT INTO category(name,sort,enabled) VALUES (?,0,1)",marker);
        categoryId=jdbc.queryForObject("SELECT id FROM category WHERE name=?",Long.class,marker);
        jdbc.update("INSERT INTO product_spu(category_id,name,brand,gluten_type,usage_text,ingredients,storage,shelf_life_days,image_url) VALUES (?,?,'麦香','HIGH','面包','小麦','干燥',180,'https://example.com/a.jpg')",categoryId,marker);
        productId=jdbc.queryForObject("SELECT id FROM product_spu WHERE category_id=?",Long.class,categoryId);
    }
    /** 按外键顺序只清理本用例的数据。 */
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM cache_invalidation WHERE cache_key=?",
                org.spring.shopproduct.cache.ProductCacheKeys.detail(productId));
        jdbc.update("DELETE FROM product_sku WHERE product_id=?",productId);
        jdbc.update("DELETE FROM product_spu WHERE id=?",productId);
        jdbc.update("DELETE FROM category WHERE id=?",categoryId);
    }

    @Test void goesOnSaleThenOffShelfWithLatestVersionAndAllSkus() throws Exception {
        sku(true,"A");sku(false,"B");
        var r=put(path(),Map.of("status","ON_SALE","expectedVersion",1),headers(path()));check(r,200,"OK");
        var data=json.readTree(r.getBody()).path("data");assertThat(data.size()).isEqualTo(13);
        assertThat(data.path("productId").asText()).isEqualTo(Long.toString(productId));
        assertThat(data.path("status").asText()).isEqualTo("ON_SALE");assertThat(data.path("version").asLong()).isEqualTo(2);
        assertThat(data.path("skus").size()).isEqualTo(2);
        assertThat(data.path("skus").get(0).path("version").asLong()).isEqualTo(1);
        assertThat(data.path("skus").get(0).path("estimatedAvailable").isNull()).isTrue();
        assertThat(data.path("skus").get(1).path("enabled").asBoolean()).isFalse();assertState("ON_SALE",2);
        // 同状态的当前版本请求也递增；同一旧版本重试必须冲突，不能覆盖其他操作者。
        check(put(path(),Map.of("status","ON_SALE","expectedVersion",2),headers(path())),200,"OK");
        check(put(path(),Map.of("status","OFF_SHELF","expectedVersion",2),headers(path())),409,"STATE_CONFLICT");assertState("ON_SALE",3);
        check(put(path(),Map.of("status","OFF_SHELF","expectedVersion",3),headers(path())),200,"OK");assertState("OFF_SHELF",4);
    }

    @Test void rejectsNoEnabledSkuAndDisabledCategoryWithoutChangingVersion() throws Exception {
        check(put(path(),onSale(),headers(path())),409,"PRODUCT_NOT_READY");sku(false,"A");
        check(put(path(),onSale(),headers(path())),409,"PRODUCT_NOT_READY");
        jdbc.update("UPDATE product_sku SET enabled=1 WHERE product_id=?",productId);
        jdbc.update("UPDATE category SET enabled=0 WHERE id=?",categoryId);
        check(put(path(),onSale(),headers(path())),409,"CATEGORY_DISABLED");assertState("OFF_SHELF",1);
    }

    @Test void canTakeOffShelfEvenWhenCategoryDisabledAndNoSkuEnabled() throws Exception {
        jdbc.update("UPDATE product_spu SET status='ON_SALE' WHERE id=?",productId);
        jdbc.update("UPDATE category SET enabled=0 WHERE id=?",categoryId);
        var r=put(path(),Map.of("status","OFF_SHELF","expectedVersion",1),headers(path()));check(r,200,"OK");
        assertThat(json.readTree(r.getBody()).path("data").path("skus").size()).isZero();assertState("OFF_SHELF",2);
    }

    @Test void validatesIdsFieldsAndVersionWithoutWrites() throws Exception {
        check(put(path(),Map.of(),headers(path())),400,"VALIDATION_ERROR");
        Object[][] invalid={{"status","INVALID"},{"status",null},{"expectedVersion",0},{"expectedVersion",-1},
                {"expectedVersion",1.5},{"expectedVersion","1"},{"expectedVersion",Long.MAX_VALUE},
                {"version",1},{"priceFen",1},{"productId","1"}};
        for(var pair:invalid){var input=new HashMap<String,Object>(onSale());input.put((String)pair[0],pair[1]);
            check(put(path(),input,headers(path())),400,"VALIDATION_ERROR");}
        for(String id:List.of("0","-1","abc","9223372036854775808")){
            var p="/api/v1/admin/products/"+id+"/sale-status";check(put(p,onSale(),headers(p)),400,"VALIDATION_ERROR");}
        var missing="/api/v1/admin/products/"+Long.MAX_VALUE+"/sale-status";
        check(put(missing,onSale(),headers(missing)),404,"PRODUCT_NOT_FOUND");assertState("OFF_SHELF",1);
    }

    @Test void rejectsUntrustedAnonymousAndWrongRequestBinding() throws Exception {
        var missing=new HttpHeaders();missing.set("X-User-Id","42");check(put(path(),onSale(),missing),401,"INVALID_INTERNAL_TOKEN");
        var forged=headers(path());forged.set("X-Internal-Token","static-secret");check(put(path(),onSale(),forged),401,"INVALID_INTERNAL_TOKEN");
        var wrong=headers(path());wrong.set("X-Internal-Token",InternalTestTokens.token("shop-product","42","user","POST",path()));
        check(put(path(),onSale(),wrong),401,"INVALID_INTERNAL_TOKEN");
        var anonymous=headers(path());anonymous.set("X-Internal-Token",InternalTestTokens.token("shop-product",null,"anonymous","PUT",path()));
        check(put(path(),onSale(),anonymous),403,"FORBIDDEN");assertState("OFF_SHELF",1);
    }

    @Test void concurrentSameVersionHasOneWinnerAndElevenConflicts() throws Exception {
        sku(true,"A");var pool=Executors.newFixedThreadPool(12);var start=new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> futures=new ArrayList<>();
            for(int i=0;i<12;i++)futures.add(pool.submit(()->{start.await();return put(path(),onSale(),headers(path()));}));
            start.countDown();int winners=0,conflicts=0;
            for(var f:futures){var r=f.get(30,TimeUnit.SECONDS);if(r.getStatusCode().value()==200){check(r,200,"OK");winners++;}
                else {check(r,409,"STATE_CONFLICT");conflicts++;}}
            assertThat(winners).isEqualTo(1);assertThat(conflicts).isEqualTo(11);assertState("ON_SALE",2);
        } finally {pool.shutdownNow();}
    }

    /** 创建合法 SKU，保留 SPU 状态和版本以验证销售接口独立职责。 */
    private void sku(boolean enabled,String suffix){jdbc.update("INSERT INTO product_sku(product_id,sku_code,spec_name,weight_gram,price_fen,enabled) VALUES (?,?,'5kg',5000,2990,?)",productId,marker+suffix,enabled);}
    private String path(){return "/api/v1/admin/products/"+productId+"/sale-status";}
    private Map<String,Object> onSale(){return Map.of("status","ON_SALE","expectedVersion",1);}
    private HttpHeaders headers(String p){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.set("X-Trace-Id","sale-status-test");
        h.set("X-Internal-Token",InternalTestTokens.token("shop-product","42","user","PUT",p));return h;}
    private ResponseEntity<String> put(String p,Object data,HttpHeaders h){return http.exchange(p,HttpMethod.PUT,new HttpEntity<>(data,h),String.class);}
    /** 任何拒绝均不能悄悄改变销售状态或版本。 */
    private void assertState(String status,long version){var row=jdbc.queryForMap("SELECT status,version FROM product_spu WHERE id=?",productId);
        assertThat(row.get("status")).isEqualTo(status);assertThat(((Number)row.get("version")).longValue()).isEqualTo(version);}
    private void check(ResponseEntity<String> r,int status,String code)throws Exception {assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(status);
        var b=json.readTree(r.getBody());assertThat(b.path("code").asText()).isEqualTo(code);
        assertThat(b.path("traceId").asText()).isEqualTo(r.getHeaders().getFirst("X-Trace-Id"));}
}
