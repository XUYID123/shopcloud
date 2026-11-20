package org.spring.shopproduct.controller;

import org.spring.shopcommon.result.Result;
import org.spring.shopproduct.service.SkuService;
import org.spring.shopproduct.vo.SkuCreatedVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开规格查询由网关转发，商品服务继续验证网关签发的内部 JWT。 */
@RestController
@RequestMapping("/api/v1/skus")
public class PublicSkuController {
    private final SkuService service;

    public PublicSkuController(SkuService service) {
        this.service = service;
    }

    @GetMapping("/{skuId}")
    public Result<SkuCreatedVO> detail(@PathVariable("skuId") String skuId) {
        return Result.success(service.detail(skuId));
    }
}
