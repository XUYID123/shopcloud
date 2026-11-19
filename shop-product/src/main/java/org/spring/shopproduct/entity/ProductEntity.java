package org.spring.shopproduct.entity;

import lombok.Getter;
import lombok.Setter;

/** SPU 数据库映射；主键由 MyBatis 回填，不直接作为接口响应。 */
@Getter
@Setter
public class ProductEntity {
    private Long id;
    private Long categoryId;
    private String name;
    private String brand;
    private String glutenType;
    private String usageText;
    private String ingredients;
    private String storage;
    private Integer shelfLifeDays;
    private String imageUrl;
    private String status; // 数据库销售状态，独立接口控制。
    private Long version; // 后台操作使用的乐观锁版本。
}
