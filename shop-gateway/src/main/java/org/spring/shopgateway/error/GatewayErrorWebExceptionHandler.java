package org.spring.shopgateway.error;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spring.shopgateway.web.GatewayRequestFilter;
import org.spring.shopgateway.web.GatewayRequestHeaders;
import org.spring.shopgateway.web.GatewayResponses;
import org.spring.shopcommon.trace.TraceContext;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/** 统一未提交的异常响应，复用 Result；不重写正常下游响应或向已提交的流追加错误 JSON。 */
@Component
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler, Ordered {
    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);
    private final GatewayResponses responses;
    /** 复用现有 WebFlux 响应组件，不使用 MVC 的 ControllerAdvice。 */
    public GatewayErrorWebExceptionHandler(GatewayResponses responses) { this.responses = responses; }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        // 异常处理器位于 WebFilter 的外层，不能假设入口 contextWrite 自动覆盖外层异常回调。
        String existing = exchange.getAttribute(GatewayRequestFilter.TRACE_ID);
        String traceId = existing != null ? existing : TraceContext.newId();
        exchange.getAttributes().put(GatewayRequestFilter.TRACE_ID, traceId);
        return Mono.defer(() -> {
            if (exchange.getResponse().isCommitted()) return Mono.error(failure);
            // 交给后面的 Sentinel(-2) 专属处理器，保留原有 429 回调及 Retry-After。
            if (BlockException.isBlockException(failure)) return Mono.error(failure);
            Throwable error = Exceptions.unwrap(failure);
            String requestId = exchange.getAttribute(GatewayRequestFilter.TRACE_ID);
            // 正常复用入口 ID；只有异常发生在入口之前且没有 ID 时才生成一次。
            exchange.getResponse().getHeaders().set(GatewayRequestHeaders.REQUEST_ID, requestId);
            exchange.getResponse().getHeaders().set(TraceContext.HEADER, requestId);
            Failure result = classify(error);
            if (result.status()==HttpStatus.INTERNAL_SERVER_ERROR) {
                log.error("event=GATEWAY_FAILURE code={} status={} type={}",result.code(),result.status().value(),error.getClass().getSimpleName(),error);
            } else if (result.status().is5xxServerError()) {
                log.error("event=GATEWAY_FAILURE code={} requestId={} status={} type={} causeType={}",result.code(),requestId,
                        result.status().value(), error.getClass().getSimpleName(),
                        error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName());
            }
            if (result.status() == HttpStatus.UNAUTHORIZED) {
                exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"shop\", error=\"invalid_token\"");
            }
            if (error instanceof ResponseStatusException statusError && statusError.getHeaders().containsHeader(HttpHeaders.ALLOW)) {
                exchange.getResponse().getHeaders().put(HttpHeaders.ALLOW, statusError.getHeaders().get(HttpHeaders.ALLOW));
            }
            return result.retryAfter() == null
                    ? responses.reject(exchange, result.status(), result.code(), result.message())
                    : responses.reject(exchange, result.status(), result.code(), result.message(), result.retryAfter());
        }).contextWrite(context -> context.put(TraceContext.REACTOR_KEY, traceId));
    }

    /** 连接/服务发现故障 503，等待下游超时 504，未知程序错误 500；不泄露底层异常 reason。 */
    private Failure classify(Throwable error) {
        if (error instanceof GatewayException known) return new Failure(known.status(), known.code(), known.getMessage(), known.retryAfterSeconds());
        if (error instanceof NotFoundException || error instanceof ConnectException || error instanceof UnknownHostException) {
            return new Failure(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "下游服务暂不可用，请稍后重试", 1L);
        }
        if (error instanceof ReadTimeoutException || error instanceof TimeoutException) {
            return new Failure(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", "下游服务响应超时", null);
        }
        if (error instanceof ResponseStatusException responseError) {
            HttpStatus status = HttpStatus.resolve(responseError.getStatusCode().value());
            if (status != null && status.isError()) {
                String code = switch (status) {
                    case BAD_REQUEST -> "BAD_REQUEST";
                    case NOT_FOUND -> "NOT_FOUND";
                    case UNAUTHORIZED -> "UNAUTHENTICATED";
                    case FORBIDDEN -> "FORBIDDEN";
                    case TOO_MANY_REQUESTS -> "RATE_LIMITED";
                    case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
                    case GATEWAY_TIMEOUT -> "UPSTREAM_TIMEOUT";
                    default -> "HTTP_" + status.value();
                };
                return new Failure(status, code, status.getReasonPhrase(), status == HttpStatus.TOO_MANY_REQUESTS ? 1L : null);
            }
        }
        return new Failure(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "网关处理失败，请稍后重试", null);
    }
    /** -3 先于 Sentinel(-2)/Boot(-1)；与 GlobalFilter 的顺序是不同层次。 */
    @Override public int getOrder() { return -3; }
    private record Failure(HttpStatus status, String code, String message, Long retryAfter) { }
}
