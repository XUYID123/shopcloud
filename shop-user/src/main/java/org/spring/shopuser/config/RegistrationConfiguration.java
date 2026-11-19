package org.spring.shopuser.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 注册基础设施配置：提供 BCrypt 编码器及严格的 JSON 文本输入规则，不处理具体注册请求。 */
@Configuration(proxyBeanMethods = false)
public class RegistrationConfiguration {
    /** 供 UserService 注入；encode 产生加盐哈希，不能拿明文密码直接存库。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 禁止 Jackson 把数字/布尔值自动变成用户名或密码字符串，保持输入契约明确。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictTextFields() {
        return builder -> builder.postConfigurer(mapper -> {
            // 对所有文本目标字段设置类型转换策略；未知字段/尾随 JSON 的严格配置在 YAML 中。
            var textual = mapper.coercionConfigFor(LogicalType.Textual);
            textual.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
            textual.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            textual.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        });
    }
}
