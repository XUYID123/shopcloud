package org.spring.shopuser.controller;

import org.spring.shopcommon.auth.UserPermissionSnapshot;
import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.exception.ErrorCode;
import org.spring.shopcommon.result.Result;
import org.spring.shopuser.service.UserPermissionService;
import org.springframework.web.bind.annotation.*;

/**
 * 提供给网关 OpenFeign 的内部只读权限接口，不属于前端公开 API，也不负责角色管理。
 * 由公共过滤器验证专用内部 JWT，再解析用户 ID，最后调用只读权限查询用例。
 * 网络隔离/TLS 仍由部署保证，不能仅凭路径包含 internal 就认为任何请求都可信。
 */
@RestController
@RequestMapping("/internal/v1/users")
public class InternalPermissionController {
    private final UserPermissionService service; // 实际执行数据库状态/权限查询。
    /** 注入只读查询用例；身份与调用范围由公共过滤器验证。 */
    public InternalPermissionController(UserPermissionService service) {
        this.service = service;
    }

    /** 返回 common.Result 包装的 UserPermissionSnapshot；不返回 UserEntity 或 passwordHash。 */
    @GetMapping("/{userId}/permissions")
    public Result<UserPermissionSnapshot> permissions(@PathVariable String userId) {
        // 公共过滤器已校验 permission-query JWT、目标用户和路径，无静态令牌旁路。
        // 路径参数保持 String，先检查格式，再检查 Long 范围，统一映射成业务参数错误 400。
        long id;
        try {
            if (!userId.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            id = Long.parseLong(userId);
        } catch (NumberFormatException invalid) { throw new BusinessException(ErrorCode.VALIDATION_ERROR); }
        // 公共 TraceServletFilter 已把网关传来的编号放入 MDC，Result 继续使用同一编号。
        // 不存在/禁用账号也可正常返回安全快照，由网关据 enabled/permissions 决定 403。
        return Result.success(service.load(id));
    }
}
