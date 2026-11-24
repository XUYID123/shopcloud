package org.spring.shopinventory.reservation;

import jakarta.servlet.http.HttpServletRequest;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.spring.shopcommon.internalauth.InternalServiceEndpoint;
import org.spring.shopcommon.internalauth.ScopedServiceJwt;
import org.spring.shopcommon.result.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Clock;

@RestController
@ConditionalOnProperty(name = ReservationController.ENABLED, havingValue = "true")
public class ReservationController {
    static final String ENABLED = "shop.inventory.reservation-lookup.enabled";
    private static final String PATH = "/internal/v1/inventory/reservations/{orderNo}";
    private static final String ALLOCATION = PATH + "/allocation";
    private final ReservationQueries reservations;

    ReservationController(ReservationQueries reservations) { this.reservations = reservations; }

    @GetMapping(PATH)
    public ResponseEntity<Result<ReservationViews.Reservation>> find(HttpServletRequest request,
            @PathVariable("orderNo") String orderNo) {
        requireService(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(reservations.find(orderNo)));
    }

    @GetMapping(ALLOCATION)
    public ResponseEntity<Result<ReservationViews.Allocation>> allocation(HttpServletRequest request,
            @PathVariable("orderNo") String orderNo) {
        requireService(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(reservations.allocation(orderNo)));
    }

    private static void requireService(HttpServletRequest request) {
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"service".equals(identity.kind())) throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ENABLED, havingValue = "true")
    static class Security {
        private final ScopedServiceJwt.Verifier verifier;

        Security(@Value("${shop.inventory.reservation-lookup.public-key-location}") Resource key) throws Exception {
            var scope = new ScopedServiceJwt.Scope("shop-order", "shop-inventory", "order:inventory:read");
            verifier = new ScopedServiceJwt.Verifier(ScopedServiceJwt.publicKey(key), scope, Clock.systemUTC());
        }

        @Bean
        InternalServiceEndpoint reservationStateEndpoint() {
            return new InternalServiceEndpoint("GET", PathPatternParser.defaultInstance.parse(PATH), verifier);
        }

        @Bean
        InternalServiceEndpoint reservationAllocationEndpoint() {
            return new InternalServiceEndpoint("GET", PathPatternParser.defaultInstance.parse(ALLOCATION), verifier);
        }
    }
}
