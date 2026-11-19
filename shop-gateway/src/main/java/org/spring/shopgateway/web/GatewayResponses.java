package org.spring.shopgateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.spring.shopcommon.result.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 组装网关拦截响应，复用 shop-common 的 Result 返回格式。
 * 网关使用 WebFlux，在这里直接写响应，不依赖 MVC 控制器的全局异常处理。
 */
@Component
public class GatewayResponses {
    private final ObjectMapper mapper; // 复用应用 JSON 配置，统一序列化 common.Result。

    /** 注入序列化器，本类只负责网关主动拒绝的响应，不重新包装正常下游响应。 */
    public GatewayResponses(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Sentinel、防刷与认证鉴权拒绝响应共用入口 traceId，不另行生成请求编号。 */
    public Result<Void> body(ServerWebExchange exchange, String code, String message) {
        // Sentinel 专属回调也经过这里；请求属性用于日志，禁止借客户端头或解析响应体猜业务码。
        exchange.getAttributes().put(org.spring.shopcommon.logging.ResultCodeContext.ATTRIBUTE,
                org.spring.shopcommon.logging.ResultCodeContext.code(code));
        Result<Void> result = Result.fail(code, message);
        // 从请求上下文取标识，使响应体 traceId 与响应头 X-Request-Id 对应。
        result.setTraceId(exchange.getAttribute(GatewayRequestFilter.TRACE_ID));
        return result;
    }

    /** 写入带 Retry-After 的拒绝响应并结束当前请求；429/部分 503 使用，不再调用下游过滤链。 */
    public Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code,
                             String message, long retryAfterSeconds) {
        return write(exchange, status, code, message, retryAfterSeconds);
    }

    /** 401/403 不携带限流重试头；503、429 仍可使用带重试秒数的重载。 */
    public Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        return write(exchange, status, code, message, null);
    }

    /** 两个公开 reject 重载共用的响应写入过程；null 重试秒数表示不添加 Retry-After。 */
    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code,
                             String message, Long retryAfterSeconds) {
        // 响应写入延迟到订阅阶段，由 WebFlux 统一驱动执行。
        return Mono.defer(() -> {
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            // 移除未提交下游响应残留的长度/压缩/验证头，保留 CORS 和请求 ID 头。
            var headers = exchange.getResponse().getHeaders();
            headers.remove("Content-Length");
            headers.remove("Content-Encoding");
            headers.remove("ETag");
            headers.remove("Last-Modified");
            headers.setCacheControl("no-store");
            // Retry-After 的数值单位为秒，至少为 1；这是重试建议，不保证届时一定放行。
            if (retryAfterSeconds != null) {
                exchange.getResponse().getHeaders().set("Retry-After", Long.toString(Math.max(1, retryAfterSeconds)));
            } else exchange.getResponse().getHeaders().remove("Retry-After");
            try {
                // 使用应用配置的 ObjectMapper 序列化对象，再以响应式方式写出 JSON 字节。
                byte[] json = mapper.writeValueAsBytes(body(exchange, code, message));
                return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(json)));
            } catch (JsonProcessingException exception) {
                // 将序列化错误交还响应式异常链，避免吞掉错误造成空白成功响应。
                return Mono.error(exception);
            }
        });
    }
}
