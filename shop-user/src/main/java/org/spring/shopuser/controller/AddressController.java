package org.spring.shopuser.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.spring.shopcommon.result.Result;
import org.spring.shopuser.dto.AddressInput;
import org.spring.shopuser.service.AddressService;
import org.spring.shopuser.vo.AddressVO;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/addresses")
public class AddressController {
    private final AddressService addresses;

    public AddressController(AddressService addresses) {
        this.addresses = addresses;
    }

    @PostMapping
    public ResponseEntity<Result<AddressVO>> create(HttpServletRequest request,
                                                      @Valid @RequestBody AddressInput input) {
        AddressVO address = addresses.create(ownerId(request), input);
        return ResponseEntity.created(URI.create("/api/v1/addresses/" + address.addressId()))
                .cacheControl(CacheControl.noStore())
                .body(Result.success(address));
    }

    @GetMapping
    public ResponseEntity<Result<List<AddressVO>>> list(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Result.success(addresses.list(ownerId(request))));
    }

    @PutMapping("/{addressId}")
    public ResponseEntity<Result<AddressVO>> replace(HttpServletRequest request,
                                                       @PathVariable("addressId") String addressId,
                                                       @Valid @RequestBody AddressInput input) {
        AddressVO address = addresses.replace(ownerId(request), parsePositiveId(addressId), input);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(address));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Result<Void>> delete(HttpServletRequest request, @PathVariable("addressId") String addressId) {
        addresses.delete(ownerId(request), parsePositiveId(addressId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.success(null));
    }

    private static long ownerId(HttpServletRequest request) {
        if (!(request.getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
                || !"user".equals(identity.kind()) || identity.userId() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return Long.parseLong(identity.userId());
    }

    static long parsePositiveId(String value) {
        if (value.matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException invalid) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR);
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR);
    }
}
