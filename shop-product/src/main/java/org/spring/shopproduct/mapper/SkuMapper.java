package org.spring.shopproduct.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.spring.shopproduct.entity.SkuEntity;

/** SKU 持久化端口，复用商品服务的数据源和本地事务。 */
@Mapper
public interface SkuMapper {
    /** 商城详情仅展示启用规格，按主键稳定排序；使用普通快照读。 */
    java.util.List<SkuEntity> selectEnabledByProduct(@org.apache.ibatis.annotations.Param("productId") long productId);

    /** 事务内按主键稳定排序并锁住当前规格，校验和响应使用同一份数据。 */
    java.util.List<SkuEntity> selectByProductForShare(@org.apache.ibatis.annotations.Param("productId") long productId);

    /** 插入并回填自增 ID；编码冲突由唯一索引原子判定。 */
    int insert(SkuEntity sku);
}
