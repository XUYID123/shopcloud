package org.spring.shopcommon.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.*;

/** MVC 异常出口与 Servlet 过滤器共用记录入口，异步完成时从原请求属性读取。 */
public final class ServletResultCodes {
    private ServletResultCodes() { }
    public static void record(HttpServletRequest request,String code) {
        request.setAttribute(ResultCodeContext.ATTRIBUTE,ResultCodeContext.code(code));
    }
    /** 兼容直接调用异常处理器的单元测试/非 HTTP 环境，无请求时不生成第二套上下文。 */
    public static void record(String code) {
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)record(attrs.getRequest(),code);
    }
}
