package org.spring.shopuser.vo;

/** 登录/刷新响应，仅 HTTPS 返回客户端；expiresIn/refreshExpiresIn 单位均为秒。 */
public record AuthTokensVO(String accessToken, String refreshToken, String tokenType,
                           long expiresIn, long refreshExpiresIn, String userId, String username) {
    /** 防止业务日志意外拼接响应对象时泄露双 Token；不影响正常 JSON 响应。 */
    @Override public String toString() { return "AuthTokensVO[credentials=REDACTED]"; }
}
