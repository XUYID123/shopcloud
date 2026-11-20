package org.spring.shopproduct.controller;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalServiceEndpoint;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopcommon.result.Result;
import org.spring.shopproduct.mapper.SkuMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Clock;

/** 只提供入库用的保质期元数据。上架前可备货，公开 SKU 的可见性规则保持独立。 */
@RestController
@ConditionalOnProperty(name = "shop.product.inventory-lookup.enabled", havingValue = "true")
public class StockSkuMetadataController {
    private static final String PATH = "/internal/v1/products/skus/{skuId}/stock-metadata";
    private final SkuMapper skus;

    public StockSkuMetadataController(SkuMapper skus) {
        this.skus = skus;
    }

    @GetMapping(PATH)
    public Result<StockSkuMetadata> find(@PathVariable("skuId") long skuId) {
        if (skuId <= 0) throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        final Integer shelfLifeDays;
        try {
            shelfLifeDays = skus.selectShelfLifeDays(skuId);
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException failure) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, failure);
        }
        if (shelfLifeDays == null) throw new BusinessException(ErrorCode.SKU_NOT_FOUND);
        return Result.success(new StockSkuMetadata(shelfLifeDays));
    }

    public record StockSkuMetadata(int shelfLifeDays) { }

    /** 公钥只信任库存调用方，且只接受这一个 GET 路径。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "shop.product.inventory-lookup.enabled", havingValue = "true")
    static class Security {
        @Bean
        InternalServiceEndpoint stockMetadataEndpoint(
                @Value("${shop.product.inventory-lookup.public-key-location}") Resource key) throws Exception {
            var scope = new ScopedServiceJwt.Scope("shop-inventory", "shop-product", "product:stock-metadata:read");
            var verifier = new ScopedServiceJwt.Verifier(ScopedServiceJwt.publicKey(key), scope, Clock.systemUTC());
            return new InternalServiceEndpoint("GET", PathPatternParser.defaultInstance.parse(PATH), verifier);
        }
    }
}
