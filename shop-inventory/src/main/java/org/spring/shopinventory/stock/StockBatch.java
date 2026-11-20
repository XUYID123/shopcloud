package org.spring.shopinventory.stock;

/** 首次入库响应快照。重放按初始数量和初始版本构造，后续库存变动不会改变原响应。 */
public record StockBatch(String batchId, String skuId, String warehouseId, String batchNo,
                         String manufacturedOn, String expiresOn, String qualityStatus, long available,
                         long reserved, long committed, long unavailable, long version) {
    static StockBatch received(long batchId, long skuId, String warehouseId, String batchNo,
                               String manufacturedOn, String expiresOn, long quantity) {
        return new StockBatch(Long.toString(batchId), Long.toString(skuId), warehouseId, batchNo,
                manufacturedOn, expiresOn, "SELLABLE", quantity, 0, 0, 0, 1);
    }
}
