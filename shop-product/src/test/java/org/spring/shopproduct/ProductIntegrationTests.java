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
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

/** 真实 HTTP/MySQL/Flyway 验证 SPU 创建、输入边界、分类状态及统一内部 JWT 保护。 */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("test")
class ProductIntegrationTests {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final String marker="test_prod_"+UUID.randomUUID().toString().replace("-", "");
    private long categoryId;

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO category(name,sort,enabled) VALUES (?,0,1)",marker);
        categoryId=jdbc.queryForObject("SELECT id FROM category WHERE name=?",Long.class,marker);
    }
    @AfterEach void cleanup() {
        // 先删本测试的 SPU，再删其分类，遵守外键且不删除其他开发数据。
        jdbc.update("DELETE FROM product_spu WHERE category_id=?",categoryId);
        jdbc.update("DELETE FROM category WHERE id=?",categoryId);
    }

    @Test void createsOffShelfProductWithServerVersionAndSafeResponse() throws Exception {
        var input=input(); input.put("name","  麦香高筋面粉  ");
        var response=post(input,headers());
        check(response,201,"OK");
        var data=json.readTree(response.getBody()).path("data");
        assertThat(data.size()).isEqualTo(13);
        assertThat(data.path("productId").isTextual()).isTrue();
        assertThat(data.path("categoryId").asText()).isEqualTo(Long.toString(categoryId));
        assertThat(data.path("name").asText()).isEqualTo("麦香高筋面粉");
        assertThat(data.path("status").asText()).isEqualTo("OFF_SHELF");
        assertThat(data.path("version").asLong()).isEqualTo(1);
        assertThat(data.path("skus").isArray()).isTrue(); assertThat(data.path("skus").size()).isZero();
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/api/v1/admin/products/"+data.path("productId").asText());
        var row=jdbc.queryForMap("SELECT * FROM product_spu WHERE id=?",data.path("productId").asLong());
        assertThat(row.get("status")).isEqualTo("OFF_SHELF"); assertThat(((Number)row.get("version")).longValue()).isEqualTo(1);
        assertThat(row.get("created_at")).isNotNull(); assertThat(row.get("updated_at")).isNotNull();
        assertThat(row.get("image_url")).isEqualTo(input.get("imageUrl"));
    }

    @Test void rejectsMissingAndDisabledCategoriesWithoutInsert() throws Exception {
        // 取一个不存在的正数分类 ID，不依赖开发库中是否已有其他分类。
        var input=input(); input.put("categoryId",Long.toString(Long.MAX_VALUE));
        check(post(input,headers()),404,"CATEGORY_NOT_FOUND");
        jdbc.update("UPDATE category SET enabled=0 WHERE id=?",categoryId);
        check(post(input(),headers()),409,"CATEGORY_DISABLED");
        assertNoRows();
    }

    @Test void rejectsInvalidFieldsAndAttemptsToSetServerControlledFields() throws Exception {
        List<Map<String,Object>> cases=new ArrayList<>(); cases.add(Map.of());
        // 同一字段的多种非法值使用列表，避免构造 Map 时重复 key。
        Object[][] invalid={{"categoryId","9223372036854775808"},{"categoryId","0"},{"name"," "},{"name",123},
                {"name","x".repeat(101)},{"brand","x".repeat(51)},{"glutenType","UNKNOWN"},
                {"usageText","x".repeat(201)},{"ingredients","x".repeat(501)},{"storage"," "},
                {"shelfLifeDays",0},{"shelfLifeDays",1096},{"shelfLifeDays",1.5},{"shelfLifeDays","180"},
                {"imageUrl","http://example.com/a.jpg"},{"imageUrl","https:///missing-host"},
                {"imageUrl","https://user:pass@example.com/a.jpg"},{"imageUrl","https://example.com/a.jpg#fragment"},
                {"status","ON_SALE"},{"version",99},{"priceFen",1},{"productId","999"}};
        for(var pair:invalid) {var input=input();input.put((String)pair[0],pair[1]);cases.add(input);}
        for(var input:cases) check(post(input,headers()),400,"VALIDATION_ERROR");
        assertNoRows();
    }

    @Test void rejectsDirectForgedWrongAudienceExpiredAndAnonymousCredentials() throws Exception {
        var missing=new HttpHeaders(); missing.setContentType(MediaType.APPLICATION_JSON);missing.set("X-User-Id","1");
        check(post(input(),missing),401,"INVALID_INTERNAL_TOKEN");
        for(String token:List.of("old-static-token",
                InternalTestTokens.token("shop-user","42","user","POST",path()),
                InternalTestTokens.token("shop-product","42","user","POST",path(),Instant.now().minusSeconds(90)))) {
            var h=headers();h.set("X-Internal-Token",token);check(post(input(),h),401,"INVALID_INTERNAL_TOKEN");
        }
        var anonymous=headers();anonymous.set("X-Internal-Token",InternalTestTokens.token("shop-product",null,"anonymous","POST",path()));
        check(post(input(),anonymous),403,"FORBIDDEN"); assertNoRows();
    }
    private Map<String,Object> input() {
        var data=new HashMap<String,Object>();data.put("categoryId",Long.toString(categoryId));data.put("name","麦香高筋面粉");
        data.put("brand","麦香");data.put("glutenType","HIGH");data.put("usageText","制作面包");data.put("ingredients","小麦");
        data.put("storage","阴凉干燥处");data.put("shelfLifeDays",180);data.put("imageUrl","https://static.example.com/flour.jpg");return data;
    }
    private static String path(){return "/api/v1/admin/products";}
    private HttpHeaders headers(){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);h.set("X-Trace-Id","product-create-test");
        h.set("X-Internal-Token",InternalTestTokens.token("shop-product","42","user","POST",path()));return h;}
    private ResponseEntity<String> post(Object input,HttpHeaders h){return http.postForEntity(path(),new HttpEntity<>(input,h),String.class);}
    private void assertNoRows(){assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_spu WHERE category_id=?",Integer.class,categoryId)).isZero();}
    private void check(ResponseEntity<String> response,int status,String code)throws Exception{
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(status);
        var body=json.readTree(response.getBody());assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("traceId").asText()).isEqualTo(response.getHeaders().getFirst("X-Trace-Id"));
    }
}
