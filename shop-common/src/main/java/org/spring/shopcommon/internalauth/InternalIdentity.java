package org.spring.shopcommon.internalauth;

/** 验签后的可信上下文；摘要用于审计，不等于可还原/直接使用的权限列表。 */
public record InternalIdentity(String userId, String kind, String permissionsDigest, String jti) {
    public static final String ATTRIBUTE = InternalIdentity.class.getName();
    public boolean anonymous() { return "anonymous".equals(kind); }
}
