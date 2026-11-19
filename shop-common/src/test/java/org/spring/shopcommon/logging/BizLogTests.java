package org.spring.shopcommon.logging;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.spring.shopcommon.exception.*;
import org.spring.shopcommon.internalauth.InternalIdentity;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.context.request.*;
import static org.assertj.core.api.Assertions.*;

/** 真实 AOP 和事务代理，验证提交失败/外层回滚不能输出 SUCCESS，以及字段和身份边界。 */
class BizLogTests {
    AnnotationConfigApplicationContext context;
    Sample service;
    Manager tx;
    final ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(BizLogAspect.class);
    final ListAppender<ILoggingEvent> logs=new ListAppender<>() {
        @Override protected void append(ILoggingEvent e){e.prepareForDeferredProcessing();super.append(e);}
    };
    @BeforeEach void start(){logs.start();logger.addAppender(logs);MDC.put("traceId","biz-test");
        context=new AnnotationConfigApplicationContext(Config.class);service=context.getBean(Sample.class);tx=context.getBean(Manager.class);}
    @AfterEach void end(){context.close();logger.detachAppender(logs);RequestContextHolder.resetRequestAttributes();MDC.clear();}

    @Test void logsCommittedIdAndVerifiedActorWithoutPayloadOrToken() {
        var req=new MockHttpServletRequest();req.addHeader("X-User-Id","evil");req.addHeader("Authorization","Bearer secret-token");
        req.setAttribute(InternalIdentity.ATTRIBUTE,new InternalIdentity("42","user","digest","jti"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        assertThat(service.create("secret-password").id()).isEqualTo("10001");
        assertThat(logs.list).hasSize(1);var e=logs.list.get(0);
        assertThat(e.getFormattedMessage()).contains("bizId=10001","userId=42","outcome=SUCCESS").doesNotContain("secret","evil");
        assertThat(e.getMDCPropertyMap()).containsEntry("traceId","biz-test");assertThat(e.getThrowableProxy()).isNull();
    }
    @Test void commitFailureAndBusinessRejectionNeverLogSuccess() {
        tx.fail=true;assertThatThrownBy(()->service.create("secret")).isInstanceOf(TransactionSystemException.class);
        assertThat(logs.list).hasSize(1);assertThat(logs.list.get(0).getFormattedMessage()).contains("outcome=FAILED").doesNotContain("SUCCESS");
        logs.list.clear();tx.fail=false;assertThatThrownBy(()->service.reject("10001")).isInstanceOf(BusinessException.class);
        assertThat(logs.list).hasSize(1);assertThat(logs.list.get(0).getFormattedMessage()).contains("outcome=REJECTED","code=STATE_CONFLICT");
    }
    @Test void outerRollbackDefersResultAndRestoresCapturedTrace() {
        new TransactionTemplate(tx).execute(status->{service.create("secret");assertThat(logs.list).isEmpty();MDC.clear();status.setRollbackOnly();return null;});
        assertThat(logs.list).hasSize(1);assertThat(logs.list.get(0).getFormattedMessage()).contains("outcome=ROLLED_BACK").doesNotContain("SUCCESS");
        assertThat(logs.list.get(0).getMDCPropertyMap()).containsEntry("traceId","biz-test");assertThat(MDC.get("traceId")).isNull();
    }
    @Test void badExpressionDoesNotChangeBusinessAndAsyncAnnotationIsRejectedBeforeExecution() {
        assertThat(service.invalid()).isEqualTo("ok");assertThat(logs.list.get(0).getFormattedMessage()).contains("bizId=-");
        assertThatThrownBy(()->service.async()).isInstanceOf(IllegalStateException.class);assertThat(service.calls).isZero();
        assertThat(LogValues.safe("a\nb\rc")).isEqualTo("a_b_c");assertThat(LogValues.safe(new Object())).isEqualTo("-");
    }

    @Configuration @EnableAspectJAutoProxy(proxyTargetClass=true) @EnableTransactionManagement
    static class Config {
        @Bean BizLogAspect aspect(){return new BizLogAspect();}
        @Bean Manager transactionManager(){return new Manager();}
        @Bean Sample sample(){return new Sample();}
    }
    public record Created(String id) { }
    public static class Sample {
        int calls;
        @BizLog(action="CREATE",bizId="#result.id") @Transactional public Created create(String password){return new Created("10001");}
        @BizLog(action="REJECT",bizId="#p0") public void reject(String id){throw new BusinessException(ErrorCode.STATE_CONFLICT);}
        @BizLog(action="BAD",bizId="T(java.lang.System).exit(0)") public String invalid(){return "ok";}
        @BizLog(action="ASYNC") public java.util.concurrent.CompletableFuture<String> async(){calls++;return null;}
    }
    /** 最小事务管理器只模拟提交结果，测试目标是切面和事务代理的先后关系。 */
    static class Manager extends AbstractPlatformTransactionManager {
        boolean fail;
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected boolean isExistingTransaction(Object t){return TransactionSynchronizationManager.isActualTransactionActive();}
        @Override protected void doBegin(Object t,TransactionDefinition d) { }
        @Override protected void doCommit(DefaultTransactionStatus s){if(fail)throw new TransactionSystemException("commit failed");}
        @Override protected void doRollback(DefaultTransactionStatus s) { }
    }
}
