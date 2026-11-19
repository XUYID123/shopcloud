package org.spring.shopproduct.service;

import org.spring.shopcommon.exception.*;
import org.spring.shopproduct.cache.ProductCacheInvalidationOutbox;
import org.spring.shopproduct.dto.ProductSaleStatusRequest;
import org.spring.shopproduct.mapper.*;
import org.spring.shopproduct.vo.*;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 销售状态用例：同库检查上架条件、按版本更新、返回当前商品与规格。 */
@Service
public class ProductSaleService {
    private final ProductMapper products;
    private final CategoryMapper categories;
    private final SkuMapper skus;
    private final ProductCacheInvalidationOutbox invalidations;

    /** 注入已有持久化端口，无远程调用或新增缓存。 */
    public ProductSaleService(ProductMapper products, CategoryMapper categories, SkuMapper skus,
                              ProductCacheInvalidationOutbox invalidations) {
        this.products=products;this.categories=categories;this.skus=skus;
        this.invalidations = invalidations;
    }

    /** 持锁范围仅覆盖本地短事务；异常回滚，不自动重试状态写入。 */
    @Transactional
    @org.spring.shopcommon.logging.BizLog(action="PRODUCT_SALE_STATUS_CHANGE",bizId="#p0")
    public ProductCreatedVO changeStatus(String id, ProductSaleStatusRequest request) {
        // 路径 ID 也采用十进制正整数，避免非法或溢出值泄漏成 500。
        long productId;
        try {
            if(id==null || !id.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            productId=Long.parseLong(id);
        } catch(NumberFormatException invalid) {throw new BusinessException(ErrorCode.VALIDATION_ERROR);}
        try {
            // 先锁 SPU，后锁分类/SKU。新增 SKU 已遵守先锁 SPU 再写 SKU 的顺序。
            var product=products.selectForUpdate(productId);
            if(product==null) throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            if(!request.expectedVersion().equals(product.getVersion()))
                throw new BusinessException(ErrorCode.STATE_CONFLICT,"商品版本已变化，请刷新后重试");
            // 下架始终可用于停止销售；只在上架时检查分类启用状态。
            if(request.status()==ProductSaleStatusRequest.SaleStatus.ON_SALE) {
                var category=categories.selectForShare(product.getCategoryId());
                if(category==null) throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
                if(!Boolean.TRUE.equals(category.getEnabled())) throw new BusinessException(ErrorCode.CATEGORY_DISABLED);
            }
            var currentSkus=skus.selectByProductForShare(productId);
            if(request.status()==ProductSaleStatusRequest.SaleStatus.ON_SALE
                    && currentSkus.stream().noneMatch(s->Boolean.TRUE.equals(s.getEnabled())))
                throw new BusinessException(ErrorCode.PRODUCT_NOT_READY);
            // 版本条件是最终的写入约束；同状态的新版本请求也计为一次成功操作并递增。
            if(products.updateSaleStatus(productId,request.status().name(),request.expectedVersion())!=1)
                throw new BusinessException(ErrorCode.STATE_CONFLICT);
            invalidations.enqueue(productId, ProductCacheInvalidationOutbox.Type.SALE_STATUS);
            // 显式构建契约字段，含启用与禁用 SKU；尚未接入库存，估算库存保持 null。
            var details=currentSkus.stream().map(s->new SkuCreatedVO(s.getId().toString(),s.getProductId().toString(),
                    s.getSkuCode(),s.getSpecName(),s.getWeightGram(),s.getPriceFen(),s.getEnabled(),null,s.getVersion())).toList();
            return new ProductCreatedVO(product.getId().toString(),product.getCategoryId().toString(),product.getName(),
                    product.getBrand(),product.getGlutenType(),product.getUsageText(),product.getIngredients(),product.getStorage(),
                    product.getShelfLifeDays(),product.getImageUrl(),request.status().name(),request.expectedVersion()+1,details);
        } catch(TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            // 数据库连接/死锁等暂时故障用 503；SQL 程序错误仍交统一异常处理返回 500。
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }
}
