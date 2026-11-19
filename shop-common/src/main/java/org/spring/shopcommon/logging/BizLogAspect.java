package org.spring.shopcommon.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.*;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.spring.shopcommon.trace.TraceContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.transaction.support.*;
import org.springframework.web.context.request.*;

/** 外包事务拦截器：方法自有事务提交失败时记录 FAILED，加入外层事务时等待最终完成。 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE+100)
public class BizLogAspect {
    private static final Logger log=LoggerFactory.getLogger(BizLogAspect.class);
    private final SpelExpressionParser parser=new SpelExpressionParser();

    /** 不拦截 Controller、不计时 Publisher，也不在此打印堆栈或改变异常语义。 */
    @Around("@annotation(spec)")
    public Object around(ProceedingJoinPoint point, BizLog spec) throws Throwable {
        Class<?> type=((org.aspectj.lang.reflect.MethodSignature)point.getSignature()).getReturnType();
        if(java.util.concurrent.Future.class.isAssignableFrom(type) || java.util.concurrent.CompletionStage.class.isAssignableFrom(type)
                || org.springframework.core.ReactiveAdapterRegistry.getSharedInstance().getAdapter(type)!=null)
            throw new IllegalStateException("@BizLog supports synchronous Service methods only");
        if(spec.level()==BizLog.Level.DEBUG && !log.isDebugEnabled()) return point.proceed();
        long start=System.nanoTime();
        String trace=MDC.get(TraceContext.MDC_KEY),user="-";
        // 身份只从验签后的属性读取；没有 HTTP 上下文的后台任务保持无用户身份。
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && attrs.getRequest().getAttribute(InternalIdentity.ATTRIBUTE) instanceof InternalIdentity identity)
            user=LogValues.safe(identity.userId());
        final String actor=user;
        Object result;
        try {result=point.proceed();}
        catch(Throwable failure) {
            String code=failure instanceof BusinessException b?b.getBusinessCode():"INTERNAL_ERROR";
            String outcome=failure instanceof BusinessException b && b.getCode()<500?"REJECTED":"FAILED";
            emit(spec,id(spec,point.getArgs(),null),actor,trace,start,outcome,code);
            throw failure;
        }
        String businessId=id(spec,point.getArgs(),result);
        // 外层事务尚未结束时不能先宣称成功；捕获标量上下文，回调不依赖当时的线程 MDC。
        if(TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    emit(spec,businessId,actor,trace,start,status==STATUS_COMMITTED?"SUCCESS":status==STATUS_ROLLED_BACK?"ROLLED_BACK":"UNKNOWN","-");
                }
            });
        } else emit(spec,businessId,actor,trace,start,"SUCCESS","-");
        return result;
    }

    /** 仅解析开发者定义的表达式；禁止类型引用、构造器及任意方法调用，失败不影响原业务。 */
    private String id(BizLog spec,Object[] args,Object result) {
        if(spec.bizId().isBlank()) return "-";
        try {
            var context=SimpleEvaluationContext.forReadOnlyDataBinding().build();
            for(int i=0;i<args.length;i++) context.setVariable("p"+i,args[i]);
            context.setVariable("result",result);
            return LogValues.safe(parser.parseExpression(spec.bizId()).getValue(context));
        } catch(RuntimeException invalid) {log.debug("event=BIZ_LOG_FIELD_UNAVAILABLE action={}",LogValues.safe(spec.action()));return "-";}
    }

    /** 输出一次结果摘要，敏感异常内容交统一异常入口处理；不记录参数、结果或异常 message。 */
    private void emit(BizLog spec,String id,String user,String trace,long start,String outcome,String code) {
        if(TraceContext.isValid(trace)) {try(var ignored=TraceContext.open(trace)){write(spec,id,user,start,outcome,code);}}
        else write(spec,id,user,start,outcome,code);
    }
    private void write(BizLog spec,String id,String user,long start,String outcome,String code) {
        String format="event=BIZ_OPERATION action={} bizId={} userId={} outcome={} code={} durationMs={}";
        Object[] values={LogValues.safe(spec.action()),id,user,outcome,code,(System.nanoTime()-start)/1_000_000};
        if(spec.level()==BizLog.Level.DEBUG) log.debug(format,values);else log.info(format,values);
    }
}
