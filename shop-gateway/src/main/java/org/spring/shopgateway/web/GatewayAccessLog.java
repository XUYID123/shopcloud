package org.spring.shopgateway.web;

import org.slf4j.*;
import org.spring.shopcommon.logging.LogValues;
import org.spring.shopcommon.trace.TraceContext;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.SignalType;
import java.util.concurrent.atomic.AtomicBoolean;

/** 网关访问日志状态；正常/取消在终止回调记录，外层异常处理器写响应时使用提交回调兜底。 */
final class GatewayAccessLog {
    private static final Logger log=LoggerFactory.getLogger(GatewayAccessLog.class);
    private final AtomicBoolean written=new AtomicBoolean();
    private final long start=System.nanoTime();
    private final String method,path,trace;
    private volatile boolean failed;
    GatewayAccessLog(ServerWebExchange exchange,String trace) {
        this.method=LogValues.safe(exchange.getRequest().getMethod().name());
        this.path=LogValues.safe(exchange.getRequest().getURI().getRawPath());this.trace=trace;
    }
    void failed(){failed=true;}
    /** 外层 ErrorWebExceptionHandler/Sentinel 的上下文不在内部过滤链，不能在原始 onError 误记为 200。 */
    void beforeCommit(ServerWebExchange exchange){if(failed)finish(exchange,"ERROR_RESPONSE","headers");}
    void terminated(ServerWebExchange exchange,SignalType signal) {
        if(signal!=SignalType.ON_ERROR || exchange.getResponse().isCommitted())
            finish(exchange,signal==SignalType.CANCEL?"CANCELLED":failed?"ERROR":"COMPLETE","complete");
    }
    private void finish(ServerWebExchange exchange,String outcome,String phase) {
        if(!written.compareAndSet(false,true))return;
        Route route=exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        int status=exchange.getResponse().getStatusCode()==null?0:exchange.getResponse().getStatusCode().value();
        if(status==0 && "COMPLETE".equals(outcome))status=200;
        String code=org.spring.shopcommon.logging.ResultCodeContext.code(exchange.getAttribute(org.spring.shopcommon.logging.ResultCodeContext.ATTRIBUTE));
        try(var ignored=TraceContext.open(trace)) {
            log.info("event=HTTP_ACCESS method={} path={} status={} code={} routeId={} durationMs={} outcome={} phase={}",
                    method,path,status,code,route==null?"-":LogValues.safe(route.getId()),(System.nanoTime()-start)/1_000_000,outcome,phase);
        }
    }
}
