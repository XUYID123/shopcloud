package org.spring.shopproduct.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.spring.shopproduct.dto.ProductListQuery;
import org.spring.shopproduct.entity.ProductEntity;
import org.spring.shopproduct.vo.ProductSummaryVO;

import java.util.List;

/** 商品持久化端口；使用项目已有的 MyBatis XML 和数据源。 */
@Mapper
public interface ProductMapper {
    long countPublicProducts(@Param("query") ProductListQuery query);

    List<ProductSummaryVO> selectPublicProducts(@Param("query") ProductListQuery query, @Param("offset") long offset);

    /** 公开详情只返回已上架且分类启用的商品，不对浏览请求加数据库行锁。 */
    ProductEntity selectVisibleById(@org.apache.ibatis.annotations.Param("id") long id);

    /** 当前读并锁住商品行，保证销售条件检查和更新期间关联商品不被改变。 */
    ProductEntity selectForUpdate(@org.apache.ibatis.annotations.Param("id") long id);

    /** 数据库原子 CAS：只允许持有预期版本的操作更新销售状态。 */
    int updateSaleStatus(@org.apache.ibatis.annotations.Param("id") long id,
                         @org.apache.ibatis.annotations.Param("status") String status,
                         @org.apache.ibatis.annotations.Param("expectedVersion") long expectedVersion);

    /** 同库事务内锁住所属 SPU，直到 SKU 插入提交，防止校验后被并发删除。 */
    @org.apache.ibatis.annotations.Select("SELECT id FROM product_spu WHERE id=#{id} FOR SHARE")
    Long selectIdForShare(@org.apache.ibatis.annotations.Param("id") long id);

    /** 插入 SPU 并回填数据库自增主键。 */
    int insert(ProductEntity product);
}
