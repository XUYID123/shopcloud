package org.spring.shopgateway.auth.policy;

import org.spring.shopgateway.auth.RbacCodec;
import org.spring.shopgateway.auth.RbacModels;
import org.springframework.http.server.PathContainer;
import java.util.List;

/** 纯权限策略：仅匹配方法/路径/路由并比较权限集合，没有 Redis、Feign、锁或线程切换。 */
public final class RbacPolicyEvaluator {
    private RbacPolicyEvaluator() { }
    /** 多条匹配规则全部生效；单条规则内部路径为“或”，可选路由集合为空表示不限制。 */
    public static List<RbacModels.Rule> matching(RbacModels.RuleSet rules, String method, PathContainer path, String routeId) {
        return rules.rules().stream().filter(rule -> rule.endpoint().methods().contains(method)
                && (rule.routeIds().isEmpty() || (routeId != null && rule.routeIds().contains(routeId)))
                && RbacCodec.patterns(rule.endpoint()).stream().anyMatch(pattern -> pattern.matches(path))).toList();
    }
    /** 无规则、禁用用户均拒绝；ALL/ANY 只解释一条规则内的权限关系。 */
    public static boolean allows(RbacModels.UserGrant user, List<RbacModels.Rule> rules) {
        return user.enabled() && !rules.isEmpty() && rules.stream().allMatch(rule -> rule.match() == RbacModels.Match.ALL
                ? user.permissions().containsAll(rule.permissions())
                : rule.permissions().stream().anyMatch(user.permissions()::contains));
    }
}
