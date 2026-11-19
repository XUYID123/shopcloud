package org.spring.shopproduct.controller;

import jakarta.validation.Valid;
import org.spring.shopcommon.result.Result;
import org.spring.shopproduct.dto.SkuCreateRequest;
import org.spring.shopproduct.service.SkuService;
import org.spring.shopproduct.vo.SkuCreatedVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

/** SKU HTTP 入口；网关校验 product:write，公共过滤器先验证内部 JWT 后才进入这里。 */
@RestController
@RequestMapping("/api/v1/admin/skus")
public class SkuController {
    private final SkuService service;
    /** 控制器只做协议适配，业务规则由服务层负责。 */
    public SkuController(SkuService service) { this.service=service; }

    /** 校验六个输入字段，成功返回 201、资源标识 Location 和统一 Result。详情 GET 后续实现。 */
    @PostMapping
    public ResponseEntity<Result<SkuCreatedVO>> create(@Valid @RequestBody SkuCreateRequest request) {
        var sku=service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/skus/"+sku.skuId())).body(Result.success(sku));
    }
}
