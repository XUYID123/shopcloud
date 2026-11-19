package org.spring.shopproduct.controller;

import org.spring.shopcommon.result.Result;
import org.spring.shopproduct.service.ProductQueryService;
import org.spring.shopproduct.vo.ProductCreatedVO;
import org.spring.shopproduct.vo.ProductPageVO;
import org.springframework.web.bind.annotation.*;

/** 商城公开查询入口；匿名白名单与内部匿名 JWT 验签均复用既有配置。 */
@RestController
@RequestMapping("/api/v1/products")
public class PublicProductController {
    private final ProductQueryService service;
    /** 将 HTTP 协议和查询用例分开，避免将后台写入逻辑混入公开查询。 */
    public PublicProductController(ProductQueryService service) {this.service=service;}

    /** 列表和详情共用网关公开路径、内部 JWT 和请求追踪。 */
    @GetMapping
    public Result<ProductPageVO> list(@RequestParam(name = "categoryId", required = false) String categoryId,
                                     @RequestParam(name = "keyword", required = false) String keyword,
                                     @RequestParam(name = "sort", required = false) String sort,
                                     @RequestParam(name = "page", required = false) String page,
                                     @RequestParam(name = "size", required = false) String size) {
        return Result.success(service.list(categoryId, keyword, sort, page, size));
    }

    /** 前端无需 Token，仍需经过网关；返回统一 Result 和已有 Trace ID。 */
    @GetMapping("/{productId}")
    public Result<ProductCreatedVO> detail(@PathVariable("productId") String productId) {
        return Result.success(service.detail(productId));
    }
}
