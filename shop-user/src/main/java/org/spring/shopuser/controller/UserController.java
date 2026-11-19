package org.spring.shopuser.controller;

import jakarta.validation.Valid;
import org.spring.shopcommon.result.Result;
import org.spring.shopuser.dto.UserRegisterRequest;
import org.spring.shopuser.service.UserService;
import org.spring.shopuser.vo.UserVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/** 用户资源的公开 HTTP 入口；当前只实现注册，对应网关 POST /api/v1/users 白名单。 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService; // 控制器只处理 HTTP，事务和注册规则交给用例服务。

    /** Spring 构造注入，避免控制器手动创建 Service 丢失事务等基础能力。 */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /** 接收 JSON 并校验 DTO，成功以 RESTful 201 和新资源 Location 返回。 */
    @PostMapping
    public ResponseEntity<Result<UserVO>> register(@Valid @RequestBody UserRegisterRequest request) {
        // @Valid 在进入方法前校验输入；数据库异常/业务冲突由 common 的 MVC 全局处理器处理。
        UserVO user = userService.register(request);
        // Location 标识新用户资源，不表示当前已经实现对应 GET 接口；响应正文使用统一 Result。
        return ResponseEntity.created(URI.create("/api/v1/users/" + user.userId()))
                .body(Result.success(user));
    }
}
