package org.spring.shopcommon.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 同步方法的 Redis 时间窗口防重复提交注解，必须通过 Spring 代理调用才能生效。
 * <p>窗口从首次占位开始；成功或异常后均保留至 TTL 到期，不缓存/重放业务响应。
 * 该注解不替代数据库唯一约束、状态机或跨窗口的业务幂等记录。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /** Redis Key 前缀；实际 Key 还包含服务/方法/动态值的摘要，不直接暴露订单号等参数。 */
    String prefix() default "idempotent:";

    /** 防重窗口，单位秒，必须为正数；应覆盖方法执行与事务提交的最大耗时。 */
    long expireTime() default 5;

    /** 重复请求的公开提示；业务异常代码固定 DUPLICATE_REQUEST，HTTP 409。 */
    String message() default "请求已提交，请勿重复操作";

    /**
     * 必填的开发者 SpEL，例如 #dto.orderId 或 #userId + ':' + #dto.orderId。
     * 同时支持 #p0/#a0/#args[0]；必须得到非空的标量值，避免所有用户共用一个空 Key。
     */
    String key();
}
