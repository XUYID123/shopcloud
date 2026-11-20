package org.spring.shopinventory.stock;

import feign.FeignException;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.internalauth.InternalJwtProtocol;
import org.spring.shopcommon.logging.BizLog;
import org.spring.shopinventory.product.StockProductClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** 编排入库。远程查商品位于事务外，批次、汇总和流水随请求绑定一起提交。 */
@Service
public class StockBatchService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final StockBatchStore store;
    private final StockProductClient products;
    private final TransactionTemplate transaction;

    public StockBatchService(StockBatchStore store, StockProductClient products, PlatformTransactionManager manager) {
        this.store = store;
        this.products = products;
        this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    @BizLog(action = "STOCK_BATCH_RECEIVE", bizId = "#result.batch.batchId")
    public Receipt receive(long actor, String key, StockBatchInput input) {
        long skuId;
        LocalDate made;
        LocalDate expiry;
        try {
            skuId = Long.parseLong(input.skuId());
            made = LocalDate.parse(input.manufacturedOn());
            expiry = LocalDate.parse(input.expiresOn());
        } catch (NumberFormatException | DateTimeParseException invalid) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        // 输入含可变长度批号，前置长度消除拼接歧义。密钥和原始请求体不进入日志。
        String hash = InternalJwtProtocol.digest(skuId + ":" + input.warehouseId() + ":" + input.batchNo().length()
                + ":" + input.batchNo() + ":" + made + ":" + expiry + ":" + input.quantity());
        try {
            var existing = store.findRequest(actor, key);
            if (existing != null) return replay(existing, hash);

            var today = LocalDate.now(BUSINESS_ZONE);
            if (made.isBefore(LocalDate.of(1000, 1, 1)) || made.isAfter(today) || !expiry.isAfter(today)) {
                throw new BusinessException(ErrorCode.INVALID_BATCH_DATES);
            }
            if (expiry.isAfter(made.plusDays(shelfLifeDays(skuId)))) {
                throw new BusinessException(ErrorCode.INVALID_BATCH_DATES);
            }

            return transaction.execute(status -> insert(actor, key, hash, input, skuId, made, expiry));
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException
                 | CannotCreateTransactionException failure) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    private Receipt insert(long actor, String key, String hash, StockBatchInput input, long skuId,
                           LocalDate made, LocalDate expiry) {
        // INSERT ON DUPLICATE KEY UPDATE 以数据库唯一键串行化同一操作人的同一个键。
        store.claimRequest(actor, key, hash);
        var claimed = store.lockRequest(actor, key);
        if (claimed == null) throw new IllegalStateException("Missing claimed stock receipt");
        if (claimed.batchId() != null) return replay(claimed, hash);

        // 先更新 SKU 汇总，再写批次及流水，给后续同一 SKU 的并发库存操作保留统一锁顺序。
        store.addSkuStock(skuId, input.warehouseId(), input.quantity());
        final long batchId;
        try {
            batchId = store.insertBatch(skuId, input.warehouseId(), input.batchNo(), made, expiry, input.quantity());
        } catch (DuplicateKeyException duplicateBatch) {
            throw new BusinessException(ErrorCode.STOCK_BATCH_EXISTS);
        }
        store.insertLedger(batchId, actor, input.quantity());
        store.completeRequest(actor, key, batchId);
        return new Receipt(StockBatch.received(batchId, skuId, input.warehouseId(), input.batchNo(),
                made.toString(), expiry.toString(), input.quantity()), false);
    }

    private Receipt replay(StockBatchStore.ReceiptBinding binding, String hash) {
        if (!binding.requestHash().equals(hash)) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        var original = store.loadInitialBatch(binding.batchId());
        if (original == null) throw new IllegalStateException("Missing stock receipt batch");
        return new Receipt(original, true);
    }

    private int shelfLifeDays(long skuId) {
        try {
            var result = products.find(skuId);
            if (result == null || !"OK".equals(result.getCode()) || result.getData() == null
                    || result.getData().shelfLifeDays() == null || result.getData().shelfLifeDays() <= 0) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            return result.getData().shelfLifeDays();
        } catch (FeignException failure) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    public record Receipt(StockBatch batch, boolean replayed) { }
}
