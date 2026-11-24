package org.spring.shopcommon.exception;

import lombok.Getter;

/**
 * MVC 服务共用的业务错误目录：每个枚举关联 HTTP 状态和默认提示。
 * 枚举名是 Result.code，httpStatus 才是 HTTP 层状态；两者不能混成一个数字判断。
 * 网关可按其协议使用独立的字符串业务码，但仍复用 common.Result 的结构。
 */
@Getter
public enum ErrorCode {
    // 输入/认证/资源类错误，通常由调用方修改请求或凭据后处理。
    VALIDATION_ERROR(400, "请求参数不正确"),
    BAD_CALLBACK(400, "支付回调无效"),
    BUSINESS_ERROR(400, "请求无法处理"),
    UNAUTHORIZED(401, "请先登录"),
    INVALID_CREDENTIALS(401, "用户名或密码错误"),
    INVALID_ACCESS_TOKEN(401, "访问令牌无效"),
    ACCESS_TOKEN_EXPIRED(401, "访问令牌已过期，请刷新"),
    INVALID_REFRESH_TOKEN(401, "刷新令牌无效或已过期，请重新登录"),
    FORBIDDEN(403, "没有操作权限"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    // 冲突类错误保持 409，让前端区分“格式错误”与“当前业务状态不允许”。
    USERNAME_EXISTS(409, "用户名已被注册"),
    ADDRESS_LIMIT_EXCEEDED(409, "收货地址最多保存20条"),
    CART_LIMIT_EXCEEDED(409, "购物车最多保存20种商品规格"),
    CATEGORY_NAME_EXISTS(409, "分类名称已存在"),
    CATEGORY_NOT_FOUND(404, "商品分类不存在"),
    CATEGORY_DISABLED(409, "商品分类已禁用"),
    PRODUCT_NOT_FOUND(404, "商品不存在"),
    SKU_NOT_FOUND(404, "商品规格不存在"),
    SKU_CODE_EXISTS(409, "SKU编码已存在"),
    STOCK_BATCH_EXISTS(409, "该规格在此仓库的批号已存在"),
    IDEMPOTENCY_CONFLICT(409, "幂等键已用于不同的请求"),
    QUOTE_EXPIRED(409, "报价已过期，请重新结算"),
    QUOTE_USED(409, "报价已用于创建订单"),
    PRICE_CHANGED(409, "报价内容已变化，请重新结算"),
    ORDER_LIMIT_EXCEEDED(422, "最多同时处理5笔未完成订单"),
    INVALID_BATCH_DATES(422, "批次生产日期、到期日或保质期不符合入库规则"),
    PRODUCT_UNAVAILABLE(422, "所选商品规格不可售"),
    DELIVERY_UNSUPPORTED(422, "收货地址超出配送范围"),
    WEIGHT_LIMIT_EXCEEDED(422, "订单商品总重不能超过500公斤"),
    ORDER_AMOUNT_LIMIT_EXCEEDED(422, "订单应付金额超出限额"),
    ADDRESS_REGION_UNSUPPORTED(422, "请选择支持的行政区"),
    PRODUCT_NOT_READY(409, "上架商品至少需要一个启用的SKU"),
    STATE_CONFLICT(409, "资源状态冲突"),
    DUPLICATE_REQUEST(409, "请求已提交，请勿重复操作"),
    UNSUPPORTED_MEDIA_TYPE(415, "请求内容类型不支持"),
    RATE_LIMITED(429, "请求过多，请稍后重试"),
    // 依赖不可用与内部未知错误分开，不能把它们伪装成客户端没有权限。
    DEPENDENCY_UNAVAILABLE(503, "服务暂时不可用，请稍后重试"),
    CIRCUIT_OPEN(503, "结算依赖暂时不可用，请稍后重试"),
    INTERNAL_ERROR(500, "系统开小差了，请稍后再试");

    private final int httpStatus; // ResponseEntity 使用的 HTTP 状态。
    private final String message; // 未提供更具体业务提示时的安全默认文案。

    /** 枚举构造时固定状态和提示，调用方通过 Lombok 生成的 getter 读取。 */
    ErrorCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    /** 兼容仅有 HTTP 状态的异常/旧接口；无法识别时选择安全的通用错误，而非暴露原始堆栈。 */
    public static ErrorCode fromHttpStatus(int status) {
        return switch (status) {
            case 400 -> VALIDATION_ERROR;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> STATE_CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            case 503 -> DEPENDENCY_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : BUSINESS_ERROR;
        };
    }
}
