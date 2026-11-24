package org.spring.shopproduct.controller;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.result.Result;
import org.spring.shopproduct.dto.CrmCatalogFilters;
import org.spring.shopproduct.service.CrmCatalogPreviewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** 本机 CRM 导入验收入口。使用专用令牌限制对采购目录的读取。 */
@RestController
@ConditionalOnProperty(name = "shop.product.crm-preview.enabled", havingValue = "true")
public class CrmCatalogPreviewController {
    private final CrmCatalogPreviewService catalog;
    private final byte[] token;

    public CrmCatalogPreviewController(CrmCatalogPreviewService catalog,
                                       @Value("${shop.product.crm-preview.token:}") String token) {
        if (!token.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("CRM preview requires a 64-character hexadecimal token");
        }
        this.catalog = catalog;
        this.token = token.getBytes(StandardCharsets.US_ASCII);
    }

    @GetMapping("/api/v1/products/crm-catalog")
    public ResponseEntity<Result<CrmCatalogPreviewService.Page>> page(
            @RequestHeader(name = "X-Crm-Preview-Token", required = false) String suppliedToken,
            @RequestParam Map<String, String> filters,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size) {
        authorize(suppliedToken);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(catalog.page(CrmCatalogFilters.parse(filters), keyword, page, size)));
    }

    @GetMapping("/api/v1/products/crm-catalog/overview")
    public ResponseEntity<Result<CrmCatalogPreviewService.Overview>> overview(
            @RequestHeader(name = "X-Crm-Preview-Token", required = false) String suppliedToken,
            @RequestParam Map<String, String> filters,
            @RequestParam(name = "keyword", required = false) String keyword) {
        authorize(suppliedToken);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(catalog.overview(CrmCatalogFilters.parse(filters), keyword)));
    }

    private void authorize(String suppliedToken) {
        if (suppliedToken == null || !MessageDigest.isEqual(token, suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
