package org.spring.shopuser.controller;


import org.spring.shopcommon.exception.BusinessException;
import org.spring.shopcommon.result.Result;
import org.springframework.web.bind.annotation.*;

/** 保留的业务异常演示接口，真实联调用它验证网关放行后不会改写下游的业务 400。 */
@RestController
@RequestMapping("api/v1")
public class TestController {

    /** 此端点故意抛业务异常，不代表已经实现用户资料查询功能。 */
    @GetMapping("/user")
    public Result<String> test(){
        // 模拟业务报错
        throw new BusinessException("用户名已被注册！");
//        return Result.success("<UNK>");
    }
}
