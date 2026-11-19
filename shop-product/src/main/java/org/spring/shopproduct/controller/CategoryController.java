package org.spring.shopproduct.controller;

import jakarta.validation.Valid;
import org.spring.shopcommon.result.Result;
import org.spring.shopproduct.dto.CategoryCreateRequest;
import org.spring.shopproduct.service.CategoryService;
import org.spring.shopproduct.vo.CategoryVO;
import org.spring.shopproduct.vo.CategoryListItemVO;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * 商品分类的 REST 入口：创建需要 product:write，公开列表允许匿名访问。
 * 创建时网关验证 Access Token 和权限；列表命中匿名白名单，二者都签发 aud=shop-product 的内部 JWT。
 * common 自动装配的 InternalAuthenticationFilter 在进入 MVC 前验证内部签名、有效期和请求绑定，
 * 并从签名载荷建立可信身份；不信任客户端填写的 X-User-Id，不接受旧静态内部令牌。
 * 因此这里仅处理输入校验与业务调用，不再叠加另一套 JWT 解析或角色查询逻辑。
 */
@RestController
@RequestMapping("/api/v1")
public class CategoryController {
    private final CategoryService service;
    public CategoryController(CategoryService service) { this.service = service; }

    /** 网关已有 GET 白名单，下游已有公开路径配置；前端无需提交 Token，仍须经过网关。 */
    @GetMapping("/categories")
    public Result<List<CategoryListItemVO>> list() {
        return Result.success(service.listEnabled());
    }

    @PostMapping("/admin/categories")
    public ResponseEntity<Result<CategoryVO>> create(@Valid @RequestBody CategoryCreateRequest request) {
        var category = service.create(request);
        // Location 指向管理员详情资源（详情 GET 待实现）；商城列表使用 GET /api/v1/categories。
        return ResponseEntity.created(URI.create("/api/v1/admin/categories/" + category.categoryId()))
                .body(Result.success(category));
    }
}
