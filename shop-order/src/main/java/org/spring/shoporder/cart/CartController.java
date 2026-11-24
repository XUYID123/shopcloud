package org.spring.shoporder.cart;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.spring.shopcommon.result.Result;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cart/items")
public class CartController {
    private final CartService cart;

    public CartController(CartService cart) {
        this.cart = cart;
    }

    @GetMapping
    public ResponseEntity<Result<List<CartItem>>> list(HttpServletRequest request) {
        return response(cart.list(owner(request)));
    }

    @PutMapping(path = "/{skuId}", consumes = "application/json")
    public ResponseEntity<Result<CartItem>> put(HttpServletRequest request, @PathVariable("skuId") String skuId,
                                                @Valid @RequestBody CartQuantityInput input) {
        return response(cart.put(owner(request), parseSkuId(skuId), input.quantity()));
    }

    @DeleteMapping("/{skuId}")
    public ResponseEntity<Result<Void>> delete(HttpServletRequest request, @PathVariable("skuId") String skuId) {
        cart.delete(owner(request), parseSkuId(skuId));
        return response(null);
    }

    private static long owner(HttpServletRequest request) {
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"user".equals(identity.kind()) || identity.userId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return Long.parseLong(identity.userId());
    }

    private static long parseSkuId(String value) {
        try {
            if (value.matches("[1-9][0-9]{0,18}")) return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }

    private static <T> ResponseEntity<Result<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(data));
    }
}
