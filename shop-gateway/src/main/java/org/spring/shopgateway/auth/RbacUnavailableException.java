package org.spring.shopgateway.auth;

/**
 * 表示权限数据/加载过程不可靠，由认证应用用例分类，再由 WebFlux 异常出口统一写 503。
 * 它与“用户明确无权”的 false/403 不同，不能通过捕获此异常自动授予访问权限。
 */
public class RbacUnavailableException extends RuntimeException {
    /** 业务上下文已明确的缓存/协议故障，提供简短内部诊断消息。 */
    public RbacUnavailableException(String message) { super(message); }
    /** 包装 JSON/路径解析等原始异常，保留原因供诊断，但响应不直接暴露此消息。 */
    public RbacUnavailableException(String message, Throwable cause) { super(message, cause); }
}
