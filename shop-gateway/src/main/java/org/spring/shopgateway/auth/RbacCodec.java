package org.spring.shopgateway.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.validation.Validator;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * 权限文档的编解码与校验边界，供 Redis 读取、冷加载回填和受控规则写入复用。
 * 既检查 JSON 能否解析，也检查 Bean Validation、规则 ID 唯一性和路径模式是否合法。
 * 拒绝的文档抛 RbacUnavailableException，最终产生 503，不把损坏数据当作“无规则则放行”。
 */
public final class RbacCodec {
    private final ObjectMapper mapper; // 本类独立副本，安全约束不影响应用其他 JSON 接口。
    private final Validator validator; // 执行 RbacModels 上的字段与集合大小约束。

    /** 复制全局 Mapper，并拒绝未知字段；避免字段拼写错误被悄悄忽略。 */
    public RbacCodec(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.validator = validator;
    }

    /** 将 Redis JSON 读取为指定文档类型；解析后仍必须做业务约束校验。 */
    public <T> T read(String json, Class<T> type) {
        // 此处限制 Java 字符数，避免在请求期间解析过大文档；Feign 解码器另有限制响应字节数。
        if (json == null || json.length() > 262144) throw new RbacUnavailableException("Invalid RBAC payload size");
        // Jackson 反序列化失败、record 构造参数错误都统一转为权限依赖故障。
        try { return validate(mapper.readValue(json, type)); }
        catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new RbacUnavailableException("Invalid RBAC document", exception);
        }
    }

    /** 通用字段校验后，继续检查注解无法表达的跨规则约束。 */
    public <T> T validate(T value) {
        // null、缺失必填字段、非法权限编码及超大集合都不能进入鉴权或缓存。
        if (value == null || !validator.validate(value).isEmpty()) {
            throw new RbacUnavailableException("RBAC document validation failed");
        }
        if (value instanceof RbacModels.RuleSet rules) {
            // 每条规则必须有独立标识；相同 ID 出现多次容易产生管理端更新歧义。
            if (rules.rules().stream().map(RbacModels.Rule::id).distinct().count() != rules.rules().size()) {
                throw new RbacUnavailableException("Duplicate RBAC rule id");
            }
            // 校验时就尝试编译每条路径，拒绝无法解析的模式，不把它当作普通未命中。
            rules.rules().forEach(rule -> patterns(rule.endpoint()));
        }
        return value;
    }

    /** 写入 Redis 前使用同一套校验，避免只能写入、却无法被网关再次读出的文档。 */
    public String write(Object value) {
        validate(value);
        try {
            // 序列化后的字符数也受限制，输入对象合法不代表最终 JSON 一定足够小。
            String json = mapper.writeValueAsString(value);
            if (json.length() > 262144) throw new RbacUnavailableException("RBAC document too large");
            return json;
        } catch (JsonProcessingException exception) {
            throw new RbacUnavailableException("Cannot encode RBAC document", exception);
        }
    }

    /** 按统一规则处理尾斜杠并编译 Spring 路径模式；返回的是本次处理的匹配器列表。 */
    public static List<PathPattern> patterns(RbacModels.Endpoint endpoint) {
        try {
            return endpoint.paths().stream().map(path -> {
                // 根路径保留一个斜杠，其他路径去掉尾斜杠，与 GatewayRequestPath 的结果保持一致。
                while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
                return PathPatternParser.defaultInstance.parse(path);
            }).toList();
        } catch (IllegalArgumentException exception) {
            throw new RbacUnavailableException("Invalid RBAC path pattern", exception);
        }
    }
}
