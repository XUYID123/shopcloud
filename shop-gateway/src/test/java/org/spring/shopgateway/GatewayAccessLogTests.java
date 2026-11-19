package org.spring.shopgateway;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.spring.shopgateway.config.ProtectionProperties;
import org.spring.shopgateway.web.*;
import org.spring.shopgateway.error.GatewayErrorWebExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 网关正常拒绝与外层异常响应日志：不得把未处理的错误误记为 200，且不重复记录。 */
class GatewayAccessLogTests {
    final ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger("org.spring.shopgateway.web.GatewayAccessLog");
    final ListAppender<ILoggingEvent> logs=new ListAppender<>(){
        @Override protected void append(ILoggingEvent e){e.prepareForDeferredProcessing();super.append(e);}
    };
    @BeforeEach void start(){logs.start();logger.addAppender(logs);}
    @AfterEach void end(){logger.detachAppender(logs);MDC.clear();}
    @Test void normalAnd429LogFinalStatusAndPreserveTraceWithoutSecrets() {
        for(var status:List.of(HttpStatus.OK,HttpStatus.TOO_MANY_REQUESTS)){
            logs.list.clear();var ex=exchange();filter().filter(ex,next->{next.getResponse().setStatusCode(status);return next.getResponse().setComplete();}).block(Duration.ofSeconds(3));
            assertThat(logs.list).hasSize(1);var e=logs.list.get(0);assertThat(e.getFormattedMessage()).contains("method=GET","status="+status.value(),"phase=complete").doesNotContain("secret");
            assertThat(e.getMDCPropertyMap()).containsEntry("traceId","gateway-access-test");
        }
    }
    @Test void outerErrorHandlerProducesOne503RatherThanFalse200() {
        var ex=exchange();var errors=new GatewayErrorWebExceptionHandler(new GatewayResponses(new ObjectMapper()));
        filter().filter(ex,next->Mono.error(new java.net.ConnectException("secret")))
                .onErrorResume(error->errors.handle(ex,error)).block(Duration.ofSeconds(3));
        assertThat(logs.list).hasSize(1);assertThat(logs.list.get(0).getFormattedMessage()).contains("status=503","code=SERVICE_UNAVAILABLE","phase=headers").doesNotContain("secret");
    }
    @Test void localRejectsAndSentinelBodyUseSameCodeWithoutReadingResponseBody() {
        var responses=new GatewayResponses(new ObjectMapper());
        for(var code:List.of("UNAUTHENTICATED","FORBIDDEN","RATE_LIMITED")) {
            logs.list.clear();var ex=exchange();var status=code.equals("UNAUTHENTICATED")?HttpStatus.UNAUTHORIZED:code.equals("FORBIDDEN")?HttpStatus.FORBIDDEN:HttpStatus.TOO_MANY_REQUESTS;
            filter().filter(ex,next->responses.reject(next,status,code,"拒绝")).block(Duration.ofSeconds(3));
            assertThat(logs.list).hasSize(1);assertThat(logs.list.get(0).getFormattedMessage()).contains("status="+status.value(),"code="+code);
        }
        // Sentinel 专属回调只调用 body()，不经过 reject()，仍必须记录相同业务码。
        logs.list.clear();var ex=exchange();
        filter().filter(ex,next->{var body=responses.body(next,"RATE_LIMITED","拒绝");assertThat(body.getCode()).isEqualTo("RATE_LIMITED");
            next.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);return next.getResponse().setComplete();}).block(Duration.ofSeconds(3));
        assertThat(logs.list.get(0).getFormattedMessage()).contains("code=RATE_LIMITED");
    }
    @Test void forwardedBusinessCodeIsNotGuessedFromHttpStatusAndUnknownFailureIsInternalError() {
        var ex=exchange();filter().filter(ex,next->{next.getResponse().setStatusCode(HttpStatus.CONFLICT);return next.getResponse().setComplete();}).block(Duration.ofSeconds(3));
        assertThat(logs.list.get(0).getFormattedMessage()).contains("status=409","code=-");
        logs.list.clear();ex=exchange();var finalEx=ex;var errors=new GatewayErrorWebExceptionHandler(new GatewayResponses(new ObjectMapper()));
        filter().filter(ex,next->Mono.error(new IllegalStateException("server-only"))).onErrorResume(error->errors.handle(finalEx,error)).block(Duration.ofSeconds(3));
        assertThat(logs.list).hasSize(1);assertThat(logs.list.get(0).getFormattedMessage()).contains("status=500","code=INTERNAL_ERROR");
    }
    private MockServerWebExchange exchange(){return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products/1?password=secret").header("X-Trace-Id","gateway-access-test").header("Authorization","Bearer secret"));}
    private GatewayRequestFilter filter(){return new GatewayRequestFilter(new ClientIpResolver(new ProtectionProperties(200,null,List.of())));}
}
