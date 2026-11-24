package org.spring.shopuser.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.spring.shopcommon.internalauth.InternalServiceEndpoint;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopcommon.result.Result;
import org.spring.shopuser.service.AddressService;
import org.spring.shopuser.vo.AddressVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Clock;

@RestController
@ConditionalOnProperty(name = OrderAddressController.ENABLED, havingValue = "true")
public class OrderAddressController {
    static final String ENABLED = "shop.user.order-address-lookup.enabled";
    private static final String PATH = "/internal/v1/users/addresses/{addressId}";
    private static final ScopedServiceJwt.Scope SCOPE =
            new ScopedServiceJwt.Scope("shop-order", "shop-user", "order:address:read");
    private final AddressService addresses;

    public OrderAddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @GetMapping(PATH)
    public Result<AddressVO> find(HttpServletRequest request, HttpServletResponse response,
                                  @PathVariable("addressId") String addressId) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"service".equals(identity.kind())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        String[] owners = request.getParameterValues("userId");
        if (owners == null || owners.length != 1) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        AddressVO address = addresses.findForOrder(
                AddressController.parsePositiveId(owners[0]), AddressController.parsePositiveId(addressId));
        return Result.success(address);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    static class Security {
        @Bean
        InternalServiceEndpoint orderAddressEndpoint(
                @Value("${shop.user.order-address-lookup.public-key-location}") Resource key) throws Exception {
            var verifier = ScopedServiceJwt.Verifier.forQueryBoundEndpoint(
                    ScopedServiceJwt.publicKey(key), SCOPE, Clock.systemUTC());
            return new InternalServiceEndpoint("GET", PathPatternParser.defaultInstance.parse(PATH), verifier);
        }
    }
}
