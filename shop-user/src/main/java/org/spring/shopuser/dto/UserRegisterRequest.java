package org.spring.shopuser.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 前端注册输入 DTO，只允许用户名和密码；不接受角色、权限、状态等服务端管理字段。
 * Controller 的 @Valid 执行字段校验，严格 Jackson 配置拒绝未声明字段及错误输入类型。
 */
@Getter
@Setter
public class UserRegisterRequest {
    // 用户名限制 ASCII 字母、数字和下划线，长度 3~32；数据库还提供大小写不敏感的唯一约束。
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$", message = "用户名须为3至32位字母、数字或下划线")
    private String username;

    // WRITE_ONLY 允许读取请求密码，但禁止把 DTO 序列化回响应时输出它。
    // 限制非空白 ASCII 和最多 72 字节对应 BCrypt 输入边界，不在日志中打印实际密码。
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^[\\x21-\\x7e]{8,72}$", message = "密码须为8至72位ASCII非空白可打印字符")
    private String password;



}
