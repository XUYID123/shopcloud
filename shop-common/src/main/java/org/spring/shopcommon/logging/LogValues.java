package org.spring.shopcommon.logging;

/** 日志字段只接受标量，并限制长度、清除控制字符；不自动序列化 DTO、实体或凭据。 */
public final class LogValues {
    private LogValues() { }
    public static String safe(Object value) {
        if (!(value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>)) return "-";
        String text=value.toString();
        return text.substring(0,Math.min(text.length(),256)).replaceAll("[\\p{Cntrl}\\s]","_");
    }
}
