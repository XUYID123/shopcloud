package org.spring.shopproduct.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.spring.shopproduct.entity.CategoryEntity;

/** 使用父 POM 已提供的 MyBatis；名称唯一性由数据库保证，无须先查询再插入。 */
@Mapper
public interface CategoryMapper {
    int insert(CategoryEntity category);

    /** 商城只读取启用分类；排序与过滤交给数据库执行，返回空集合代表暂无分类。 */
    java.util.List<CategoryEntity> selectEnabled();

    /** 创建商品的事务内共享锁查询，防止分类在校验后被并发禁用或删除。 */
    CategoryEntity selectForShare(@org.apache.ibatis.annotations.Param("id") long id);
}
