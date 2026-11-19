package org.spring.shopcommon.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.slf4j.*;
import org.spring.shopcommon.exception.*;
import org.spring.shopcommon.internalauth.*;
import org.spring.shopcommon.result.*;
import org.spring.shopcommon.trace.TraceServletFilter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 完整 MVC 异常转换/序列化及过滤器短路：日志业务码必须等于响应业务码，且每请求仅一条访问日志。 */
class ResultCodeLogTests {
    final ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(ServletAccessLog.class);
    final ListAppender<ILoggingEvent> logs=new ListAppender<>(){
        @Override protected void append(ILoggingEvent e){e.prepareForDeferredProcessing();super.append(e);}
    };
    final ObjectMapper json=new ObjectMapper();
    MockMvc mvc;
    @BeforeEach void start(){logs.start();logger.addAppender(logs);mvc=MockMvcBuilders.standaloneSetup(new Endpoints())
            .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new TraceServletFilter()).build();}
    @AfterEach void end(){logger.detachAppender(logs);MDC.clear();}

    @Test void businessMissingDependencyAndUnknownCodesMatchResponses() throws Exception {
        Object[][] cases={{"/business",409,"STATE_CONFLICT"},{"/missing",404,"PRODUCT_NOT_FOUND"},
                {"/dependency",503,"DEPENDENCY_UNAVAILABLE"},{"/unknown",500,"INTERNAL_ERROR"}};
        for(var c:cases){logs.list.clear();var r=mvc.perform(get((String)c[0]).header("X-Trace-Id","code-test")).andReturn();check(r,(int)c[1],(String)c[2]);}
    }
    @Test void invalidJsonAndDtoValidationAreRecordedWithoutRejectedValues() throws Exception {
        check(mvc.perform(post("/json").header("X-Trace-Id","code-test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"secret-token\"}")).andReturn(),400,"VALIDATION_ERROR");
        logs.list.clear();check(mvc.perform(get("/validation").header("X-Trace-Id","code-test")).andReturn(),400,"VALIDATION_ERROR");
    }
    @Test void directResultFailureIsCapturedEvenWithHttp200AndFramework405IsCovered() throws Exception {
        check(mvc.perform(get("/direct").header("X-Trace-Id","code-test")).andReturn(),200,"STATE_CONFLICT");
        logs.list.clear();check(mvc.perform(post("/direct").header("X-Trace-Id","code-test")).andReturn(),405,"METHOD_NOT_ALLOWED");
    }
    @Test void internalJwtRejectionBeforeMvcRecords401And403Codes() throws Exception {
        var verifier=mock(InternalJwtVerifier.class);
        var auth=new InternalAuthenticationFilter(verifier,json,new InternalAuthProperties("unused",List.of()),"shop-product");
        for(boolean anonymous:List.of(false,true)) {
            logs.list.clear();var req=new MockHttpServletRequest("GET","/protected");req.addHeader("X-Trace-Id","filter-code-test");
            if(anonymous){req.addHeader("X-Internal-Token","valid-anonymous");when(verifier.verify(anyString(),anyString(),anyString(),isNull()))
                    .thenReturn(new InternalIdentity(null,"anonymous","digest","jti"));}
            var res=new MockHttpServletResponse();new TraceServletFilter().doFilter(req,res,(r,s)->auth.doFilter(r,s,(a,b)->{throw new AssertionError("Must not forward");}));
            String code=anonymous?"FORBIDDEN":"INVALID_INTERNAL_TOKEN";
            assertThat(res.getStatus()).isEqualTo(anonymous?403:401);assertThat(json.readTree(res.getContentAsString()).path("code").asText()).isEqualTo(code);
            assertThat(logs.list).hasSize(1);assertThat(logs.list.get(0).getFormattedMessage()).contains("code="+code);
        }
    }

    /** 比较真实响应、请求属性、访问日志和 MDC 的同一份业务码/追踪 ID。 */
    private void check(MvcResult r,int status,String code)throws Exception{
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        assertThat(json.readTree(r.getResponse().getContentAsString()).path("code").asText()).isEqualTo(code);
        assertThat(r.getRequest().getAttribute(ResultCodeContext.ATTRIBUTE)).isEqualTo(code);
        assertThat(logs.list).hasSize(1);var e=logs.list.get(0);assertThat(e.getFormattedMessage()).contains("status="+status,"code="+code).doesNotContain("secret-token");
        assertThat(e.getMDCPropertyMap()).containsEntry("traceId","code-test");assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
    public record Input(long id) { }
    @RestController static class Endpoints {
        @GetMapping("/business") Object business(){throw new BusinessException(ErrorCode.STATE_CONFLICT);}
        @GetMapping("/missing") Object missing(){throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);}
        @GetMapping("/dependency") Object dependency(){throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,new IllegalStateException("server-only"));}
        @GetMapping("/unknown") Object unknown(){throw new IllegalStateException("server-only");}
        @GetMapping("/direct") Result<Void> direct(){return Result.fail(ErrorCode.STATE_CONFLICT.name(),"拒绝");}
        @PostMapping("/json") Result<Input> input(@RequestBody Input input){return Result.success(input);}
        @GetMapping("/validation") Object validation()throws Exception {
            var errors=new org.springframework.validation.BeanPropertyBindingResult(new Object(),"input");
            errors.addError(new org.springframework.validation.FieldError("input","name","secret-token",false,null,null,"参数不正确"));
            throw new MethodArgumentNotValidException(new org.springframework.core.MethodParameter(Endpoints.class.getDeclaredMethod("input",Input.class),0),errors);
        }
    }
}
