package org.spring.shopproduct.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.spring.shopcommon.internalauth.InternalServiceEndpoint;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopcommon.result.Result;
import org.spring.shopproduct.mapper.SkuMapper;
import org.spring.shopproduct.vo.CartSkuVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@RestController
@ConditionalOnProperty(name = CartSkuMetadataController.ENABLED, havingValue = "true")
public class CartSkuMetadataController {
    static final String ENABLED = "shop.product.cart-lookup.enabled";
    private static final String PATH = "/internal/v1/products/skus/cart-metadata";
    private final SkuMapper skus;

    public CartSkuMetadataController(SkuMapper skus) {
        this.skus = skus;
    }

    @GetMapping(PATH)
    public Result<List<CartSkuVO>> find(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"service".equals(identity.kind())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        String[] values = request.getParameterValues("skuId");
        if (values == null || values.length == 0 || values.length > 20) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        var ids = new ArrayList<Long>(values.length);
        var seen = new HashSet<Long>();
        try {
            for (String value : values) {
                long id = Long.parseLong(value);
                if (id <= 0 || !Long.toString(id).equals(value) || !seen.add(id)) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR);
                }
                ids.add(id);
            }
        } catch (NumberFormatException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return Result.success(skus.selectCartMetadata(ids));
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    static class Security {
        @Bean
        InternalServiceEndpoint cartMetadataEndpoint(
                @Value("${shop.product.cart-lookup.public-key-location}") Resource key) throws Exception {
            var scope = new ScopedServiceJwt.Scope("shop-order", "shop-product", "product:cart-metadata:read");
            var verifier = ScopedServiceJwt.Verifier.forQueryBoundEndpoint(
                    ScopedServiceJwt.publicKey(key), scope, Clock.systemUTC());
            return new InternalServiceEndpoint("GET", PathPatternParser.defaultInstance.parse(PATH), verifier);
        }
    }
}
