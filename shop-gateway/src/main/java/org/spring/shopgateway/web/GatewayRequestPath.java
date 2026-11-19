package org.spring.shopgateway.web;

import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.server.PathContainer;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * 防刷、白名单、RBAC 共用的路径匹配工具，避免同一请求在不同保护层得到不同逻辑路径。
 * 处理服务发现别名、HTTP 分段解码/矩阵参数及尾部斜杠；不修改实际 HTTP 转发地址。
 * 它不是权限缓存，也不在这里判断请求是否有权。
 */
public final class GatewayRequestPath {
    // 纯解析工具，不能把请求路径保存成共享可变字段。
    private GatewayRequestPath() { }

    /** 读取框架匹配出的路由，返回用于保护策略比较的 PathContainer。 */
    public static PathContainer resolve(ServerWebExchange exchange) {
        // rawPath 保留原始编码，由框架按路径段处理一次；不自行重复 URLDecoder 解码。
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        PathContainer path = PathContainer.parsePath(exchange.getRequest().getURI().getRawPath());
        var elements = path.elements();
        // 只有首个路径段等于真实 lb:// 目标服务名时才移除，不能随意去掉任何第一个目录。
        if (route != null && "lb".equalsIgnoreCase(route.getUri().getScheme()) && elements.size() >= 2
                && elements.get(1) instanceof PathContainer.PathSegment segment
                && segment.valueToMatch().equals(route.getUri().getHost())) {
            path = elements.size() == 2 ? PathContainer.parsePath("/") : path.subPath(2);
        }
        // 将尾斜杠别名统一，但保留根路径；避免只换一个斜杠就绕开额度或白名单匹配。
        while (path.elements().size() > 1
                && path.elements().get(path.elements().size() - 1) instanceof PathContainer.Separator) {
            path = path.subPath(0, path.elements().size() - 1);
        }
        return path;
    }
}
