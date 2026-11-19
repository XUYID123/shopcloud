package org.spring.shopuser.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** 登录输入仅允许用户名/密码，不接受用户 ID、角色或权限；不生成包含密码的 toString。 */
@Getter @Setter
public class UserLoginRequest {
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$", message = "用户名格式不正确")
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^[\\x21-\\x7e]{8,72}$", message = "密码格式不正确")
    private String password;
}
