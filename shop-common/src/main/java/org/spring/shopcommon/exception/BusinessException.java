package org.spring.shopcommon.exception;


import lombok.Getter;

/**
 * MVC 业务用例主动抛出的可预期拒绝，携带 HTTP 状态和统一业务码两个不同维度。
 * GlobalExceptionHandler 读取这些字段构建响应；未被事务代码捕获时也会触发数据库事务回滚。
 * 不用于网关异步依赖故障，网关使用自己的 Result/Mono 拒绝分支。
 */
@Getter
public class BusinessException extends RuntimeException {
    private final Integer code; // HTTP 状态，例如 409，不是用户名重复等细分业务码。
    private final String businessCode; // 例如 USERNAME_EXISTS，写入 Result.code。

    // 常用构造函数 1：只传提示信息（默认错误码 400）
    public BusinessException(String message) {
        this(ErrorCode.BUSINESS_ERROR, message);
    }

    // 常用构造函数 2：自定义错误码和提示信息
    public BusinessException(Integer code, String message) {
        // RuntimeException 的 message 保存对调用方可见的业务提示，不承载密码或原始系统异常。
        super(message);
        this.code = code == null || code < 400 || code > 599 ? 400 : code;
        this.businessCode = ErrorCode.fromHttpStatus(this.code).name();
    }

    /** 使用枚举的标准提示，避免各控制器重复拼写业务码/状态。 */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    /** 保留枚举状态和业务码，仅替换可见提示，方便同一错误类型给出具体业务说明。 */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getHttpStatus();
        this.businessCode = errorCode.name();
    }

    /** 保留内部故障原因供服务端诊断；对外提示仍只使用枚举安全文案。 */
    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(),cause);
        this.code=errorCode.getHttpStatus();this.businessCode=errorCode.name();
    }
}
