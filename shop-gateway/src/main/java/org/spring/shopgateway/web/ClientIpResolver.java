package org.spring.shopgateway.web;

import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import io.netty.util.NetUtil;
import org.spring.shopgateway.config.ProtectionProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 解析用于防刷的客户端 IP，只有直接连接网关的对端可信时才读取 X-Forwarded-For。
 * 生产环境需确保可信代理正确维护该请求头，并限制客户端绕过代理直连业务服务。
 */
@Component
public class ClientIpResolver {
    private final List<IpSubnetFilterRule> trustedProxies; // 仅受控前置代理网段，不是所有业务用户的 IP 白名单。

    /** 从 gateway.protection.trusted-proxies 创建只读匹配规则，收到请求时不临时解析配置。 */
    public ClientIpResolver(ProtectionProperties properties) {
        // 在启动阶段解析配置；非法 IP 或网段应直接暴露配置错误。
        trustedProxies = properties.trustedProxies().stream().map(ClientIpResolver::subnet).toList();
    }

    /**
     * 从真实连接对端出发，沿可信代理链向前寻找首个不可信地址。
     * GatewayRequestFilter 必须在清理原始代理头之前调用本方法，并把结果保存为 CLIENT_IP。
     */
    public String resolve(ServerHttpRequest request) {
        InetSocketAddress peer = request.getRemoteAddress();
        if (peer == null || peer.getAddress() == null) {
            // 无法识别来源时共用保守的 unknown 额度，不从请求头随意获取 IP。
            return "unknown";
        }
        String originalPeer = peer.getAddress().getHostAddress();
        if (!trusted(peer)) {
            // 直连客户端无法通过伪造 X-Forwarded-For 切换 Redis 计数桶。
            return originalPeer;
        }
        // 合并多个同名头；限制总长度和跳数，避免解析过大的代理链。
        String forwarded = String.join(",", request.getHeaders().getOrEmpty("X-Forwarded-For"));
        if (forwarded.isBlank() || forwarded.length() > 2048) {
            return originalPeer;
        }
        String[] hops = forwarded.split(",", -1);
        if (hops.length > 20) {
            return originalPeer;
        }
        // X-Forwarded-For 越靠右越接近网关。遇到首个不可信节点即停止，
        // 更左侧的内容可能是该节点自行伪造的，不能直接取最左边作为客户端。
        InetSocketAddress current = peer;
        for (int i = hops.length - 1; i >= 0 && trusted(current); i--) {
            // 只接受 IPv4/IPv6 字面量，不进行 DNS 查询；非法链回退到连接对端。
            byte[] literal = NetUtil.createByteArrayFromIpAddressString(hops[i].trim());
            if (literal == null) {
                return originalPeer;
            }
            try {
                current = new InetSocketAddress(InetAddress.getByAddress(literal), 0);
            } catch (UnknownHostException impossible) {
                return originalPeer;
            }
        }
        // 使用解析后的地址文本，减少同一 IP 的不同写法造成的计数差异。
        return current.getAddress().getHostAddress();
    }

    /** 空的可信代理列表不会匹配任何地址。 */
    private boolean trusted(InetSocketAddress address) {
        return trustedProxies.stream().anyMatch(rule -> rule.matches(address));
    }

    /** 同时支持单个 IP 和 CIDR；未写掩码时按单个 IPv4 /32 或 IPv6 /128 匹配。 */
    private static IpSubnetFilterRule subnet(String cidr) {
        String[] parts = cidr.split("/", -1);
        byte[] literal = NetUtil.createByteArrayFromIpAddressString(parts[0]);
        if (literal == null || parts.length > 2) {
            throw new IllegalArgumentException("Trusted proxy must be an IP literal or CIDR: " + cidr);
        }
        int bits = parts.length == 2 ? Integer.parseInt(parts[1]) : literal.length * 8;
        return new IpSubnetFilterRule(parts[0], bits, IpFilterRuleType.ACCEPT);
    }
}
