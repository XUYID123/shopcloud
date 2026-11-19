package org.spring.shopgateway.web;

import org.springframework.http.HttpHeaders;

/**
 * 网关入口和认证过滤器共用的 HTTP 头约定，避免两个位置维护不同的身份头清理名单。
 * HttpHeaders 按大小写不敏感处理名称，因此客户端换成小写或混合大小写也不能绕过删除。
 * 本类只定义名称与清理行为，不验证 JWT，也不查询任何权限。
 */
public final class GatewayRequestHeaders {
    public static final String REQUEST_ID = org.spring.shopcommon.trace.TraceContext.LEGACY_HEADER; // X-Trace-Id 的同值兼容头。
    public static final String USER_ID = "X-User-Id"; // 仅认证鉴权通过后，由网关写入的可信用户 ID。
    public static final String FORWARDED_FOR = "X-Forwarded-For"; // 清理后按可信来源重建的代理来源链。

    // 纯静态工具类，不保存每次请求状态，无需构造实例。
    private GatewayRequestHeaders() { }

    /** 删除后继续处理，既不信任外部身份，也不因客户端提交这些头而直接拒绝。 */
    public static void removeIdentity(HttpHeaders headers) {
        // 删除全部同名值，而非只取第一个；保留 Authorization 和正常的自定义业务头。
        headers.remove(USER_ID);
        headers.remove("X-User-Roles");
        headers.remove("X-User-Permissions");
    }
}
