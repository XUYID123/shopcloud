package org.spring.shopproduct.vo;

/** 与已有 Category 契约一致；ID 用字符串避免 JavaScript 大整数精度丢失。 */
public record CategoryVO(String categoryId, String name, int sort, boolean enabled) { }
