package org.spring.shopcommon.result;


import lombok.extern.slf4j.Slf4j;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Spring MVC/Servlet 服务的统一异常响应入口，由 shop-user 显式 @Import 注册。
 * 业务异常按约定状态与业务码返回，未知系统错误对外只给通用提示。
 * 它依赖 MVC，不是 WebFlux 网关的处理器；网关复用 Result，但自行写响应式拒绝响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler implements org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice<Object> {

    /** MVC 在序列化前访问 Java Result 对象即可获码，不包装或消费响应流。也覆盖直接返回 Result.fail 的接口。 */
    @Override public boolean supports(org.springframework.core.MethodParameter method,
            Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converter) {return true;}
    @Override public Object beforeBodyWrite(Object body,org.springframework.core.MethodParameter method,
            org.springframework.http.MediaType contentType,Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converter,
            org.springframework.http.server.ServerHttpRequest request,org.springframework.http.server.ServerHttpResponse response) {
        if(body instanceof Result<?> result && request instanceof org.springframework.http.server.ServletServerHttpRequest servlet)
            org.spring.shopcommon.logging.ServletResultCodes.record(servlet.getServletRequest(),result.getCode());
        return body;
    }

    /** Controller/Service 抛出的可预期业务拒绝，例如重名、无权、参数状态冲突。 */
    // 1. 专门捕获自定义的业务异常
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        org.spring.shopcommon.logging.ServletResultCodes.record(e.getBusinessCode());
        // 可预期拒绝由访问日志和可选业务日志输出业务码；只有系统故障在这里打印一次堆栈。
        if(e.getCode()>=500) log.error("event=DEPENDENCY_FAILURE code={}",e.getBusinessCode(),e);
        // 使用异常自带的 code（比如 400），或者直接返回 400
        var response = ResponseEntity.status(e.getCode());
        if (ErrorCode.RATE_LIMITED.name().equals(e.getBusinessCode())) response.header(HttpHeaders.RETRY_AFTER, "1");
        return response.body(Result.fail(e.getBusinessCode(), e.getMessage()));
    }

    /** 未知错误在服务日志中保留诊断信息，对外返回统一 500，不直接回传异常消息。 */
    // 2. 捕获其他未知的系统异常（如空指针、数据库异常等）
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknownException(Exception e) {
        org.spring.shopcommon.logging.ServletResultCodes.record(ErrorCode.INTERNAL_ERROR.name());
        log.error("event=SYSTEM_FAILURE code={}",ErrorCode.INTERNAL_ERROR.name(),e);
        return ResponseEntity.internalServerError()
                .body(Result.fail(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.getMessage()));
    }

    /** DTO 输入校验失败的统一处理，选择一条可读提示，同时保护密码等被拒绝字段值。 */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // 不输出 rejectedValue 或完整校验异常，避免将用户密码写入响应或日志。
        request.setAttribute(org.spring.shopcommon.logging.ResultCodeContext.ATTRIBUTE,ErrorCode.VALIDATION_ERROR.name(),WebRequest.SCOPE_REQUEST);
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .filter(value -> value != null && !value.isBlank())
                .findFirst().orElse(ErrorCode.VALIDATION_ERROR.getMessage());
        return new ResponseEntity<>(Result.fail(ErrorCode.VALIDATION_ERROR.name(), message), headers, status);
    }

    /** 覆盖 MVC 框架其他标准异常的默认正文，仍保留其 HTTP 状态与必要响应头。 */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // 例如不支持的方法/媒体类型仍返回框架指定状态，只把正文换成 Result。
        ErrorCode errorCode = ErrorCode.fromHttpStatus(status.value());
        request.setAttribute(org.spring.shopcommon.logging.ResultCodeContext.ATTRIBUTE,errorCode.name(),WebRequest.SCOPE_REQUEST);
        return new ResponseEntity<>(Result.fail(errorCode.name(), errorCode.getMessage()), headers, status);
    }
}
