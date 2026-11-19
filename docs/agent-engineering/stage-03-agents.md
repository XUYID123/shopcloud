# 阶段 3 AGENTS.md 验收

新增 [AGENTS.md](../../AGENTS.md)。根级约束覆盖 Maven 业务模块；当前未发现须覆盖的局部规则。详细内容由 Docs 维护。技能入口路径已核对，实际七份文件在下一阶段创建与复验。

## 新增商品演练

1. 由 AGENTS.md 跳转 [组件目录](../architecture/component-catalog.md)、[业务现状](../business/README.md)和[数据库](../database/README.md)。
2. 在 [ProductController](../../shop-product/src/main/java/org/spring/shopproduct/controller/ProductController.java) 找到已有 POST、@Valid、Result 和 VO；在 [ProductService](../../shop-product/src/main/java/org/spring/shopproduct/service/ProductService.java) 找到分类校验、业务异常和事务。新需求先确认与已有接口的差异，复用审计与内部凭据链。
3. 持久化沿用 product schema 和分类外键/锁；新下架 SPU 本身无需新缓存或 MQ。涉及启用与公开读路径时再对照现有失效机制。测试非法分类、未授权、约束冲突和事务回滚。
4. 核对中间件强制评估及消息专项清单。AGENTS 的链接可达，规则与项目事实、DoD 一致。
