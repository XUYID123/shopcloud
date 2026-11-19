package org.spring.shopproduct.vo;

/** 商城分类列表项：只公开分类编号、名称和顺序，编号用字符串保留大整数精度。 */
public record CategoryListItemVO(String categoryId, String name, int sort) { }
