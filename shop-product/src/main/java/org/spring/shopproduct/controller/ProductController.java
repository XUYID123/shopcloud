package org.spring.shopproduct.controller;

import jakarta.validation.Valid;
import org.spring.shopcommon.result.Result;
import org.spring.shopproduct.dto.ProductCreateRequest;
import org.spring.shopproduct.service.ProductService;
import org.spring.shopproduct.vo.ProductCreatedVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/** 商品管理 HTTP 入口；product:write 在网关校验，下游公共过滤器先验内部 JWT。 */
@RestController
@RequestMapping("/api/v1/admin/products")
public class ProductController {
    private final ProductService service;
    private final org.spring.shopproduct.service.ProductSaleService sales;
    public ProductController(ProductService service, org.spring.shopproduct.service.ProductSaleService sales) {
        this.service=service;this.sales=sales;
    }

    /** 更新独立销售状态子资源；入口鉴权沿用网关和公共内部 JWT 过滤器。 */
    @PutMapping("/{productId}/sale-status")
    public Result<ProductCreatedVO> changeSaleStatus(@PathVariable("productId") String productId,
            @Valid @RequestBody org.spring.shopproduct.dto.ProductSaleStatusRequest request) {
        return Result.success(sales.changeStatus(productId,request));
    }

    /** 创建成功返回 201 和资源 Location；详情 GET 在后续业务实现。 */
    @PostMapping
    public ResponseEntity<Result<ProductCreatedVO>> create(@Valid @RequestBody ProductCreateRequest request) {
        var product=service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/products/"+product.productId()))
                .body(Result.success(product));
    }
}
