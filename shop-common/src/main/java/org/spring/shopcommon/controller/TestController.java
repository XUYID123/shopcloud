package org.spring.shopcommon.controller;

import org.spring.shopcommon.result.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** common 历史示例控制器，仅展示 Result.success；不属于商城用户注册或内部权限接口。 */
@RestController
@RequestMapping("/api" )
public class TestController {


    /** 主动运行/扫描 common 示例时，POST /api/test 返回固定测试数据。 */
    @PostMapping("/test")
public Result<String> test(){
    return Result.success("success");
}

}
