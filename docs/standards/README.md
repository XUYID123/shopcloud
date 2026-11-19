# Java 与 API 工程规则

业务开发使用[六步流程](development-lifecycle.md)。

- Controller 解析输入、用 Jakarta Validation 并返回专用 VO。Service 维护业务规则与事务。Mapper 使用参数绑定，动态列采用允许列表。
- 遵循 Java 17、当前模块分包和构造注入。改动遵守既有调用方和数据契约。金额、重量沿用整数最小单位。
- API 复用 Result、业务异常码及其 HTTP 映射。新契约同步 OpenAPI 和调用方。透出该角色可见字段。
- 使用 SLF4J 参数化日志、公共 Trace 和 @BizLog。记录动作、主体 ID 和分类后的依赖错误。保护凭据、内部 Token 和个人信息。
- 事务由 Service 管理。验证代理、回滚和锁范围。可靠提交后操作使用持久补偿记录，业务锁内避免外部调用。见[数据库](../database/README.md)。
- 交付遵守 [Definition of Done](definition-of-done.md)。
