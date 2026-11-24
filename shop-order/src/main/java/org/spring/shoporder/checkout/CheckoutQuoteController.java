package org.spring.shoporder.checkout;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.servlet.http.HttpServletRequest;
import org.spring.shopcommon.checkout.PurchaseItems;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.spring.shopcommon.result.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@ConditionalOnProperty(name = CheckoutModuleConfiguration.ENABLED, havingValue = "true")
public class CheckoutQuoteController {
    private final CheckoutQuoteService checkout;

    public CheckoutQuoteController(CheckoutQuoteService checkout) {
        this.checkout = checkout;
    }

    @PostMapping(path = "/api/v1/checkout/quotes", consumes = "application/json")
    public ResponseEntity<Result<CheckoutQuote>> create(HttpServletRequest request, @RequestBody QuoteInput input) {
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"user".equals(identity.kind()) || identity.userId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        var quote = checkout.create(Long.parseLong(identity.userId()), PurchaseItems.positiveId(input.addressId()), input.items());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(Result.success(quote));
    }

    public record QuoteInput(@JsonDeserialize(using = PurchaseItems.StringId.class) String addressId,
                             List<PurchaseItems.Item> items) { }
}
