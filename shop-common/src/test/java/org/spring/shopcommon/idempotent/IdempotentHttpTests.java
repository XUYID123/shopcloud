package org.spring.shopcommon.idempotent;

import org.junit.jupiter.api.Test;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.result.GlobalExceptionHandler;
import org.spring.shopcommon.result.Result;
import org.spring.shopcommon.trace.TraceServletFilter;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 层验证共用 Result/409/503 与 Trace ID，不增设生产用测试端点。 */
class IdempotentHttpTests {
    @Test void duplicateAndDependencyFailureUseExistingBusinessErrorContract() throws Exception {
        var store = mock(RedisIdempotentStore.class);
        when(store.acquire(anyString(), any())).thenReturn(true, false).thenThrow(new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE));
        var factory = new AspectJProxyFactory(new TestController()); factory.setProxyTargetClass(true);
        factory.addAspect(new IdempotentAspect(store, new IdempotentKeyResolver("test-http")));
        TestController proxy = factory.getProxy();
        var mvc = MockMvcBuilders.standaloneSetup(proxy)
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new TraceServletFilter()).build();
        for (int status : new int[]{200, 409, 503}) {
            mvc.perform(post("/test-idempotent/42").header("X-Trace-Id", "idempotent-http-trace"))
                    .andExpect(status().is(status)).andExpect(header().string("X-Trace-Id", "idempotent-http-trace"))
                    .andExpect(jsonPath("$.traceId").value("idempotent-http-trace"))
                    .andExpect(jsonPath("$.code").value(status == 200 ? "OK" : status == 409 ? "DUPLICATE_REQUEST" : "DEPENDENCY_UNAVAILABLE"));
        }
    }
    @RestController
    public static class TestController {
        @PostMapping("/test-idempotent/{id}") @Idempotent(key = "#id")
        public Result<String> submit(@PathVariable String id) { return Result.success(id); }
    }
}
