# shop-cache

MVC/后台任务使用的同步缓存公共 JAR：RedisTemplate + Redisson + Resilience4j。只读数据使用短 TTL 空值缓存、按 Key 分布式锁/Double-Check、随机 TTL；依赖读取/写入/回源分别保护，显式降级结果不入缓存。

本模块默认关闭；业务服务按需添加 `com.dong:shop-cache:1.0-SNAPSHOT`，合并 [配置示例](examples/application.yml)，设置 `shop.cache.enabled=true`。两个客户端只连接配置的业务 Redis（本项目默认 6381），不修改网关响应式 RBAC 或 6380 防刷流程。

```java
// query 为独立 Spring 查询 Bean，在数据库工作线程内建立只读事务；null 表示确实不存在。
UserProfileVO profile = cache.get("user-profile:v1:" + userId,
        UserProfileVO.class, () -> query.find(userId));
```

完整源码：[SafeCacheTemplate](src/main/java/org/spring/shopcache/SafeCacheTemplate.java)、[保护执行器](src/main/java/org/spring/shopcache/CacheDependencyExecutor.java)、[可编译 Service 示例](src/test/java/org/spring/shopcache/example/UserProfileCacheService.java)。

[接入、字段解释、执行流程、事务边界与测试记录](../docs/18-SafeCacheTemplate公共缓存组件.md)。仅适用于只读幂等 loader，禁止直接在 WebFlux I/O 线程调用或用降级结果进行身份授权。当前为单节点 Redis 连接配置，不包含基础设施主备部署。

从项目根目录执行：

```text
mvn -pl shop-cache -am package
```

集成测试依赖已运行的本地业务 Redis 6381，测试前不会启动或重建容器；只清理随机测试命名空间。
