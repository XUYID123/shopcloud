package org.spring.shopcommon.idempotent;

import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 只负责注解配置校验、SpEL 计算与 Key 规范化，不执行 Redis 或业务方法。
 * 只缓存开发者在注解中声明的表达式，不缓存请求数据或每次请求的求值上下文。
 */
public class IdempotentKeyResolver {
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer parameterNames = new DefaultParameterNameDiscoverer();
    private final ConcurrentMap<String, Expression> expressions = new ConcurrentHashMap<>();
    private final String applicationName;

    public IdempotentKeyResolver(String applicationName) {
        this.applicationName = applicationName;
    }

    /** 服务与完整方法签名参与摘要：不同服务、方法及重载不能因为相同参数而相互拦截。 */
    public String resolve(Object target, Method method, Object[] args, Idempotent annotation) {
        validate(method, annotation);
        try {
            // MethodBasedEvaluationContext 自动提供 #p0/#a0/参数名；#args 是显式提供的数组别名。
            // 表达式仅来自可信源码注解，参数只是求值数据，不能把请求文本作为 SpEL 再解析。
            var context = new MethodBasedEvaluationContext(target, method, args, parameterNames);
            context.setVariable("args", args);
            Object value = expressions.computeIfAbsent(annotation.key(), parser::parseExpression).getValue(context);
            if (!(value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                    || value instanceof UUID || value instanceof Enum<?>)) {
                throw invalid(method);
            }
            String dynamicKey = value.toString();
            if (dynamicKey.isBlank() || dynamicKey.length() > 1024) throw invalid(method);
            // 长度编码避免边界拼接歧义；不对 DTO/文件/请求对象执行任意 JSON 序列化。
            String input = part(applicationName) + part(method.toGenericString()) + part(dynamicKey);
            return annotation.prefix() + digest(input);
        } catch (RuntimeException error) {
            // 不附带 SpEL 原始异常/表达式值，它们可能包含账号、订单号或敏感 DTO 的 toString。
            throw invalid(method);
        }
    }

    private static void validate(Method method, Idempotent annotation) {
        if (annotation.expireTime() <= 0 || annotation.expireTime() > 31_536_000
                || !annotation.prefix().matches("[A-Za-z0-9:_.-]{1,128}")
                || annotation.key().isBlank() || annotation.key().length() > 1024
                || annotation.message().isBlank() || annotation.message().length() > 256) {
            throw invalid(method);
        }
    }

    private static String part(String value) { return value.length() + ":" + value; }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static IllegalStateException invalid(Method method) {
        return new IllegalStateException("Invalid @Idempotent configuration/key for " + method.getDeclaringClass().getName() + "." + method.getName());
    }
}
