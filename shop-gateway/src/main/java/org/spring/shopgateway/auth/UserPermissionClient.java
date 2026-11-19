package org.spring.shopgateway.auth;

import org.spring.shopcommon.auth.PermissionCacheKeys;
import org.spring.shopcommon.auth.UserPermissionSnapshot;
import org.spring.shopcommon.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * 网关调用 shop-user 的 OpenFeign 声明接口，实际实现由 Spring Cloud 生成代理。
 * 默认通过 Nacos/LoadBalancer 按 shop-user 找实例；配置 user-service-url 时才直接访问指定地址。
 * 这是同步 HTTP 调用，调用方必须使用 permissionFeignScheduler，不能在 Netty I/O 线程直接执行。
 * contextId 区分同一服务的 Feign 子容器；configuration 只定制本客户端的超时、解码和日志。
 */
@FeignClient(name = "shop-user", contextId = "userPermissions", url = "${gateway.auth.user-service-url:}",
        configuration = PermissionFeignConfiguration.class)
public interface UserPermissionClient {
    /**
     * 查询数据库计算的有效权限，不传递前端权限或角色。
     * @param userId 已通过 JWT 验证的用户 ID，填入 URL 路径
     * @param internalToken 网关逐请求签发的最小权限 permission-query JWT
     * @param requestId 外部请求入口生成的同一编号，供用户服务日志和统一返回关联
     * @return 沿用 common.Result 的安全权限快照，不包含密码或数据库实体
     */
    @GetMapping("/internal/v1/users/{userId}/permissions")
    Result<UserPermissionSnapshot> permissions(@PathVariable("userId") String userId,
            @RequestHeader(PermissionCacheKeys.INTERNAL_TOKEN_HEADER) String internalToken,
            @RequestHeader("X-Request-Id") String requestId);
}
