package org.spring.shopuser.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 用户权限功能的基础装配：启用配置绑定和 Spring 定时任务支持。
 * EnableScheduling 让 PermissionCacheInvalidator 的重试方法被调度，不表示引入消息队列或 Pub/Sub。
 * 数据库和 6381 Redis 连接继续使用该模块已有的 Boot 配置，不在这里创建额外实例。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(UserPermissionProperties.class)
public class UserPermissionConfiguration { }
