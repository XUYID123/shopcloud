package org.spring.shopproduct.service;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopproduct.cache.ProductCacheInvalidationOutbox;
import org.spring.shopproduct.dto.SkuCreateRequest;
import org.spring.shopproduct.entity.SkuEntity;
import org.spring.shopproduct.mapper.ProductMapper;
import org.spring.shopproduct.mapper.SkuMapper;
import org.spring.shopproduct.vo.SkuCreatedVO;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 新增规格用例：验证父商品、写入规格、将可预期冲突转换为业务码。 */
@Service
public class SkuService {
    private final ProductMapper products;
    private final SkuMapper skus;
    private final ProductCacheInvalidationOutbox invalidations;

    /** 仅依赖同服务持久化端口，不持数据库锁进行远程调用。 */
    public SkuService(ProductMapper products, SkuMapper skus, ProductCacheInvalidationOutbox invalidations) {
        this.products=products; this.skus=skus;
        this.invalidations = invalidations;
    }

    /** 共享锁与插入位于同一本地事务；任何未处理的运行时异常均回滚。 */
    @Transactional
    @org.spring.shopcommon.logging.BizLog(action="SKU_CREATE",bizId="#result.skuId")
    public SkuCreatedVO create(SkuCreateRequest request) {
        // HTTP 层验证格式，这里补上 Long 上界检查，溢出返回 400。
        long productId;
        try { productId=Long.parseLong(request.productId()); }
        catch (NumberFormatException invalid) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
        try {
            // OFF_SHELF 的 SPU 也允许准备规格；创建 SKU 不改变 SPU 销售状态。
            if (products.selectIdForShare(productId)==null) throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            var entity=new SkuEntity();
            entity.setProductId(productId); entity.setSkuCode(request.skuCode()); entity.setSpecName(request.specName());
            entity.setWeightGram(request.weightGram()); entity.setPriceFen(request.priceFen()); entity.setEnabled(request.enabled());
            // 不采用“先查编码再写”的竞态判断：唯一索引覆盖所有并发请求和其他写入来源。
            if (skus.insert(entity)!=1 || entity.getId()==null) throw new IllegalStateException("SKU insert did not return ID");
            invalidations.enqueue(productId, ProductCacheInvalidationOutbox.Type.SKU_CHANGE);
            return new SkuCreatedVO(entity.getId().toString(), Long.toString(productId), entity.getSkuCode(),
                    entity.getSpecName(), entity.getWeightGram(), entity.getPriceFen(), entity.getEnabled(), null, 1);
        } catch (DuplicateKeyException duplicate) {
            // 不提交主键，该 INSERT 的唯一业务冲突是 sku_code，统一返回 409。
            throw new BusinessException(ErrorCode.SKU_CODE_EXISTS);
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            // 死锁/连接等暂时性故障返回 503，不盲目重试结果可能不明确的写入；SQL 程序错误保持 500。
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }
}
