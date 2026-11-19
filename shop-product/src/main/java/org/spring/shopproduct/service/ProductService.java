package org.spring.shopproduct.service;

import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopproduct.dto.ProductCreateRequest;
import org.spring.shopproduct.entity.ProductEntity;
import org.spring.shopproduct.mapper.CategoryMapper;
import org.spring.shopproduct.mapper.ProductMapper;
import org.spring.shopproduct.vo.ProductCreatedVO;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 创建商品用例：同库事务内检查分类并写入 SPU；不自动上架或创建 SKU/库存。 */
@Service
public class ProductService {
    private final ProductMapper products;
    private final CategoryMapper categories;
    public ProductService(ProductMapper products, CategoryMapper categories) {
        this.products=products; this.categories=categories;
    }

    @Transactional
    @org.spring.shopcommon.logging.BizLog(action="PRODUCT_CREATE",bizId="#result.productId")
    public ProductCreatedVO create(ProductCreateRequest request) {
        // JSON ID 用字符串传输，在这里检查 Long 上界；禁止溢出变成未分类的程序异常。
        long categoryId;
        try { categoryId=Long.parseLong(request.categoryId()); }
        catch (NumberFormatException invalid) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
        try {
            // 共享锁保持到事务提交，校验后分类不能被并发禁用或删除；不持锁调用远程服务。
            var category=categories.selectForShare(categoryId);
            if (category==null) throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
            if (!Boolean.TRUE.equals(category.getEnabled())) throw new BusinessException(ErrorCode.CATEGORY_DISABLED);
            // 显式映射允许写入的字段；状态/版本始终由 INSERT 固定。
            var entity=new ProductEntity();
            entity.setCategoryId(categoryId); entity.setName(request.name()); entity.setBrand(request.brand());
            entity.setGlutenType(request.glutenType().name()); entity.setUsageText(request.usageText());
            entity.setIngredients(request.ingredients()); entity.setStorage(request.storage());
            entity.setShelfLifeDays(request.shelfLifeDays()); entity.setImageUrl(request.imageUrl());
            if (products.insert(entity)!=1 || entity.getId()==null) throw new IllegalStateException("Product insert did not return ID");
            // 返回契约内字段，无规格时 skus=[]；数据库时间不作为当前响应字段。
            return new ProductCreatedVO(entity.getId().toString(), Long.toString(categoryId), entity.getName(),
                    entity.getBrand(), entity.getGlutenType(), entity.getUsageText(), entity.getIngredients(),
                    entity.getStorage(), entity.getShelfLifeDays(), entity.getImageUrl(), "OFF_SHELF", 1, List.of());
        } catch (TransientDataAccessException | DataAccessResourceFailureException | RecoverableDataAccessException unavailable) {
            // 不对结果可能已提交的写入做盲目重试；程序错误继续交给全局 500 处理。
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, unavailable);
        }
    }
}
