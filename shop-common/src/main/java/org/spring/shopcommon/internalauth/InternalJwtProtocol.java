package org.spring.shopcommon.internalauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HexFormat;

/** 签发与验证共享的协议常量，不持有密钥、请求状态或权限缓存。 */
public final class InternalJwtProtocol {
    public static final String HEADER = "X-Internal-Token";
    public static final String TYPE = "shop-internal+jwt";
    public static final String ISSUER = "shop-gateway";
    private InternalJwtProtocol() { }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    /** 排序且编码边界，确保不同节点对同一权限快照计算出相同摘要。 */
    public static String permissionsDigest(Collection<String> permissions) {
        return digest(permissions.stream().sorted().map(value -> value.length() + ":" + value).reduce("", String::concat));
    }
}
