package org.spring.shopcommon.trace;

import org.slf4j.MDC;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * 全项目唯一的追踪约定：HTTP 两个头、MDC 和 Reactor Context 都承载同一个关联 ID。
 * ID 仅用于检索日志，绝不能用于判断身份、权限、幂等性或客户端来源。
 */
public final class TraceContext {
    public static final String HEADER = "X-Trace-Id";
    public static final String LEGACY_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";
    // 与未来 Micrometer Tracing 的上下文键隔离，不暗示本组件已创建 span。
    public static final String REACTOR_KEY = "shop.traceId";
    public static final String REQUEST_ATTRIBUTE = TraceContext.class.getName() + ".id";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceContext() { }

    /** 生成 8 字节随机数的 16 位十六进制表示；概率唯一，不声称有限长度 ID 数学上永不重复。 */
    public static String newId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 限制长度和字符，防止日志注入、逗号拼接、多行或巨大文本进入 MDC/响应头。 */
    public static boolean isValid(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,64}");
    }

    /** 多值头视为歧义而重新生成；不从攻击者提交的多个值中任取其一。 */
    public static String singleValid(List<String> values) {
        return values != null && values.size() == 1 && isValid(values.get(0)) ? values.get(0) : null;
    }

    /** 同步边界临时绑定 traceId，退出时恢复原值；可用于响应式链隔离出的阻塞 Feign 任务。 */
    public static Scope open(String traceId) {
        if (!isValid(traceId)) throw new IllegalArgumentException("Invalid trace ID");
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, traceId);
        return () -> {
            MDC.remove(MDC_KEY);
            if (previous != null) MDC.put(MDC_KEY, previous);
        };
    }

    /** close 不声明受检异常，让 try-with-resources 不干扰原业务的异常语义。 */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
