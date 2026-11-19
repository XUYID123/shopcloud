package org.spring.shopcommon.logging;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.spring.shopcommon.trace.TraceContext;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一次逻辑请求共享一个完成状态；异步分派和容器回调不会重复打印。 */
public final class ServletAccessLog implements AsyncListener {
    private static final Logger log=LoggerFactory.getLogger(ServletAccessLog.class);
    private static final String ATTRIBUTE=ServletAccessLog.class.getName();
    private final long start=System.nanoTime();
    private final AtomicBoolean written=new AtomicBoolean();
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private volatile boolean failed;
    private ServletAccessLog(HttpServletRequest request,HttpServletResponse response){this.request=request;this.response=response;}
    public static ServletAccessLog start(HttpServletRequest request,HttpServletResponse response) {
        if(request.getAttribute(ATTRIBUTE) instanceof ServletAccessLog existing) return existing;
        var state=new ServletAccessLog(request,response);request.setAttribute(ATTRIBUTE,state);return state;
    }
    public void failed(){failed=true;}
    /** 同步请求在过滤链返回时记录；异步请求等待容器真正完成。 */
    public void returned() {
        if(request.isAsyncStarted()) {
            try {request.getAsyncContext().addListener(this);return;}
            catch(IllegalStateException completed) { /* 完成与注册竞争时退回一次性输出。 */ }
        }
        finish();
    }
    private void finish() {
        if(!written.compareAndSet(false,true)) return;
        String trace=(String)request.getAttribute(TraceContext.REQUEST_ATTRIBUTE);
        if(TraceContext.isValid(trace)){try(var ignored=TraceContext.open(trace)){write();}}else write();
    }
    private void write() {
        Object identity=request.getAttribute(InternalIdentity.ATTRIBUTE);
        String user=identity instanceof InternalIdentity i?LogValues.safe(i.userId()):"-";
        int status=failed && response.getStatus()<400?500:response.getStatus();
        String code=ResultCodeContext.code(request.getAttribute(ResultCodeContext.ATTRIBUTE));
        // 未经过统一出口的原生响应保留 HTTP_ 前缀，不凭状态猜测特定业务原因。
        if("-".equals(code))code=failed?"INTERNAL_ERROR":"HTTP_"+status;
        log.info("event=HTTP_ACCESS method={} path={} status={} code={} userId={} durationMs={} outcome={}",
                LogValues.safe(request.getMethod()),LogValues.safe(request.getRequestURI()),status,code,user,
                (System.nanoTime()-start)/1_000_000,failed?"ERROR":"COMPLETE");
    }
    @Override public void onComplete(AsyncEvent event){finish();}
    @Override public void onTimeout(AsyncEvent event){failed=true;}
    @Override public void onError(AsyncEvent event){failed=true;}
    @Override public void onStartAsync(AsyncEvent event){event.getAsyncContext().addListener(this);}
}
