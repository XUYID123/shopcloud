# 当前架构

Java 17 / Boot 4.0.9 / Cloud 2025.1.4 / Alibaba 2025.1.0.0。根 reactor 聚合基础父层、common、cache、messaging、gateway、user、product、cart、order、pay。common、cache 和 messaging 为库。Gateway 走 WebFlux 并通过 Nacos 发现服务；user 和 product 分别管理自己的 MySQL schema。Gateway 的 RBAC 冷加载走 Feign 到 user。

请求链为入口清洗与 Trace → Sentinel、防刷、JWT/RBAC → 内部 JWT → Servlet 验签 → Controller 校验 DTO → Service 执行业务与事务 → Mapper/XML → VO 和统一响应。业务包按 controller、service、mapper、entity、dto、vo 分层，基础设施按 auth、cache、trace、logging 划分。参阅[配置](../../shop-gateway/src/main/resources/application.yml)、[公共组件](component-catalog.md)与[审计](../agent-engineering/stage-01-audit.md)。
