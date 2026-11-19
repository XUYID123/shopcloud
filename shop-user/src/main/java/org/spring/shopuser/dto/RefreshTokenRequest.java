package org.spring.shopuser.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 刷新/登出只传 Refresh Token；用户身份由服务器反向索引确认，不能由客户端指定。 */
@Getter @Setter
public class RefreshTokenRequest {
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @NotBlank(message = "刷新令牌不能为空")
    @Size(max = 128, message = "刷新令牌格式不正确")
    private String refreshToken;
}
