package org.spring.shopcommon.logging;

import java.lang.annotation.*;

/** 同步 Service 操作日志；只选择业务 ID，不记录整份参数或结果。通过 Spring 代理调用才生效。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BizLog {
    String action(); // 稳定的操作名，例如 PRODUCT_CREATE。
    String bizId() default ""; // 只读 SpEL，可用 #p0、#p1、#result；只能输出标量。
    Level level() default Level.INFO; // 高频查询使用 DEBUG，不产生默认 INFO 业务日志。
    enum Level { INFO, DEBUG }
}
