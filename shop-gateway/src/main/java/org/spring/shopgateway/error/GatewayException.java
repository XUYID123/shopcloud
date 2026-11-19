package org.spring.shopgateway.error;

import org.springframework.http.HttpStatus;

/** 网关主动拒绝的类型化错误，固定公开消息由 WebFlux 全局异常入口写入统一 Result。 */
public class GatewayException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Long retryAfterSeconds;

    /** 根因仅保留在服务端，响应消息不接受外部请求或底层异常 message。 */
    private GatewayException(HttpStatus status, String code, String message, Long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    /** 凭据缺失或无效，由异常处理器补充 Bearer 挑战头。 */
    public static GatewayException unauthenticated(Throwable cause) {
        return new GatewayException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请提供有效的登录凭据", null, cause);
    }
    /** 前端仅对明确过期触发单次刷新，避免对坏签名等所有 401 盲目循环刷新。 */
    public static GatewayException accessTokenExpired(Throwable cause) {
        return new GatewayException(HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_EXPIRED", "访问令牌已过期，请刷新", null, cause);
    }
    /** 身份可靠，但当前请求所需权限不满足。 */
    public static GatewayException forbidden() {
        return new GatewayException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问该资源", null, null);
    }
    /** 依赖/执行资源不可靠，不可伪装成用户明确无权。 */
    public static GatewayException authorizationUnavailable(Throwable cause) {
        return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "AUTHORIZATION_UNAVAILABLE", "权限服务暂不可用，请稍后重试", 1L, cause);
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Long retryAfterSeconds() { return retryAfterSeconds; }
}
