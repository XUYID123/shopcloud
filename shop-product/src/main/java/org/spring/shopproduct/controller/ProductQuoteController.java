package org.spring.shopproduct.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.spring.shopcommon.checkout.ProductQuote;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalIdentity;
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
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Clock;
import java.util.List;

@RestController
@ConditionalOnProperty(name = ProductQuoteController.ENABLED, havingValue = "true")
public class ProductQuoteController {
    static final String ENABLED = "shop.product.order-quote.enabled";
    private static final String PATH = "/internal/v1/products/quotes";
    private final SkuMapper skus;

    public ProductQuoteController(SkuMapper skus) {
        this.skus = skus;
    }

    @PostMapping(path = PATH, consumes = "application/json")
    public ResponseEntity<Result<ProductQuote>> quote(HttpServletRequest request, @RequestBody PurchaseItems input) {
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"service".equals(identity.kind())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        var quantities = PurchaseItems.validated(input.items());
        List<ProductQuote.Line> lines;
        try {
            lines = skus.selectQuoteLines(List.copyOf(quantities.keySet()));
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
        if (lines.size() != quantities.size()) throw new BusinessException(ErrorCode.PRODUCT_UNAVAILABLE);
        long weight = lines.stream()
                .mapToLong(line -> line.weightGram() * quantities.get(Long.parseLong(line.skuId()))).sum();
        if (weight > 500_000) throw new BusinessException(ErrorCode.WEIGHT_LIMIT_EXCEEDED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(new ProductQuote(lines)));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    static class Security {
        @Bean
        InternalServiceEndpoint productQuoteEndpoint(
                @Value("${shop.product.order-quote.public-key-location}") Resource key) throws Exception {
            var scope = new ScopedServiceJwt.Scope("shop-order", "shop-product", "order:product:quote");
            var verifier = new ScopedServiceJwt.Verifier(ScopedServiceJwt.publicKey(key), scope, Clock.systemUTC());
            return new InternalServiceEndpoint("POST", PathPatternParser.defaultInstance.parse(PATH), verifier);
        }
    }
}
