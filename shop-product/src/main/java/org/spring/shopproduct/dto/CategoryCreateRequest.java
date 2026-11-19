package org.spring.shopproduct.dto;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

/** 分类创建输入。只接收名称、排序和启用状态，ID/时间由数据库生成。 */
@Getter
@Setter
public class CategoryCreateRequest {
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称不能超过50个字符")
    private String name;
    @NotNull(message = "排序值不能为空")
    @Min(value = 0, message = "排序值不能小于0")
    @Max(value = 10000, message = "排序值不能超过10000")
    private Integer sort;
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;

    /** Jackson 绑定时先归一化，再交给 Bean Validation，长度按实际保存的名称检查。 */
    public void setName(String value) { name = value == null ? null : value.strip(); }
}
