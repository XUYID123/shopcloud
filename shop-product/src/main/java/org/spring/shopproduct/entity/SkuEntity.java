package org.spring.shopproduct.entity;

import lombok.Getter;
import lombok.Setter;

/** SKU 写入映射；MyBatis 回填主键。状态、版本和数据库实体不直接透出给接口。 */
@Getter
@Setter
public class SkuEntity {
    private Long id; // 数据库生成的 SKU 主键。
    private Long productId; // 所属 SPU，通过外键保证有效关联。
    private String skuCode; // 全局唯一的业务编码。
    private String specName; // 规格名称，例如 5kg袋装。
    private Long weightGram; // 整数克，避免单位混用。
    private Long priceFen; // 整数分，避免浮点金额误差。
    private Boolean enabled; // 是否允许使用该规格。
    private Long version; // 查询详情时返回数据库版本，创建时由 SQL 固定为 1。
}
