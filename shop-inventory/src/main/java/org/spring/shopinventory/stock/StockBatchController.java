package org.spring.shopinventory.stock;

import jakarta.validation.Valid;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.result.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;
import java.security.Principal;

/** 后台库存入口。路由要求 inventory:write，操作人取自下游验签后的身份。 */
@RestController
public class StockBatchController {
    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9._:-]{16,128}");
    private final StockBatchService service;

    public StockBatchController(StockBatchService service) {
        this.service = service;
    }

    @PostMapping(path = "/api/v1/admin/inventory/batches", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Result<StockBatch>> create(Principal identity,
                                                     @RequestHeader HttpHeaders headers,
                                                     @Valid @RequestBody StockBatchInput input) {
        if (identity == null) throw new BusinessException(ErrorCode.FORBIDDEN);
        var keys = headers.get("Idempotency-Key");
        if (keys == null || keys.size() != 1 || !REQUEST_KEY.matcher(keys.get(0)).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "需要一个有效的 Idempotency-Key 请求头");
        }
        var receipt = service.receive(Long.parseLong(identity.getName()), keys.get(0), input);
        return ResponseEntity.status(receipt.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(Result.success(receipt.batch()));
    }
}
