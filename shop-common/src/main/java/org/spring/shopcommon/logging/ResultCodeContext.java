package org.spring.shopcommon.logging;

/** Servlet/WebFlux 共用的业务码属性约定；不依赖线程变量或 Servlet 类型，网关也可安全加载。 */
public final class ResultCodeContext {
    public static final String ATTRIBUTE=ResultCodeContext.class.getName()+".code";
    private ResultCodeContext() { }
    /** 业务码是开发者定义的稳定标识，防止换行、长文本或异常消息变成日志字段。 */
    public static String code(Object value) {
        return value instanceof String text && text.matches("[A-Z][A-Z0-9_]{0,63}")?text:"-";
    }
}
