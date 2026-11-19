package org.spring.shopcommon.result;

import lombok.Data;
import org.slf4j.MDC;
import org.spring.shopcommon.exception.ErrorCode;

import java.io.Serializable;
import org.spring.shopcommon.trace.TraceContext;

/**
 * 所有模块共用的 JSON 响应契约；HTTP 状态由 Controller/异常处理器设置，不能只看 body.code。
 * MVC 服务通常从 MDC 取得 traceId；WebFlux 网关在 GatewayResponses 中用 exchange 的入口编号覆盖。
 * T 只放接口允许返回的数据，例如 UserVO/权限快照，不能直接放含密码哈希的数据库实体。
 */
@Data
public class Result<T> implements Serializable {
    private static final long serialVersionUID = 1L; // Java 序列化版本，不是 HTTP 接口版本。

    private String code;     // 业务码；HTTP 状态由 Controller / 异常处理器设置
    private String message;  // 对调用方可见的提示，不把系统异常堆栈直接放在这里。
    private T data; // 成功的业务数据；失败时通常为 null。
    private String traceId; // 用于日志/请求关联，不代表用户身份或授权凭据。

    /** 成功响应工厂，业务码固定 OK，HTTP 200/201 等由调用方按接口语义选择。 */
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode("OK");
        result.setMessage("操作成功");
        result.setData(data);
        result.setTraceId(currentTraceId());
        return result;
    }
    /** 兼容传 HTTP 数字状态的旧调用，将其转换成统一业务枚举码，不在此写 HTTP 响应。 */
    public static <T> Result<T> fail(Integer code, String message){
        return fail(ErrorCode.fromHttpStatus(code == null ? 500 : code).name(), message);
    }

    /** 失败响应工厂，允许网关提供 RATE_LIMITED/UNAUTHENTICATED 等明确的业务码。 */
    public static <T> Result<T> fail(String code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setTraceId(currentTraceId());
        return result;
    }

    /**
     * Servlet 请求由公共 TraceServletFilter 先设置 MDC；无上下文时提供工厂级兜底编号。
     * 响应式处理不能依赖线程 MDC 自动传播，网关调用方会显式写入 exchange 中的同一个 traceId。
     */
    private static String currentTraceId() {
        String traceId = MDC.get(TraceContext.MDC_KEY);
        return traceId == null ? TraceContext.newId() : traceId;
    }
}
