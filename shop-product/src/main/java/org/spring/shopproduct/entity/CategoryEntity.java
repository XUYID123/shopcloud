package org.spring.shopproduct.entity;

import lombok.Getter;
import lombok.Setter;

/** 分类数据库映射，insert 后回填主键；不直接作为 HTTP 输出。 */
@Getter
@Setter
public class CategoryEntity {
    private Long id;
    private String name;
    private Integer sort;
    private Boolean enabled;
}
