package org.spring.shopgateway.protection;

/**
 * 已匹配的策略，只携带执行器需要的名称和额度，不暴露配置对象及匹配细节。
 * 名称参与 Redis 键生成，修改名称相当于使用一组新的计数键。
 * @param name 策略唯一名称，例如 all/register/login；不是 URL 或用户角色
 * @param limit 当前策略在公共滑动窗口内，允许同一 IP 通过的次数
 */
public record AbusePolicy(String name, int limit) {
}
