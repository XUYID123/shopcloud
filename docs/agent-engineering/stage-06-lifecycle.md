# 阶段 6 开发生命周期验收

[六步流程](../standards/development-lifecycle.md)已接入 [AGENTS.md](../../AGENTS.md) 与 [feature-development](../../.agents/skills/feature-development/SKILL.md)。三个中间件始终评估，复杂决策先过设计审查，简单 CRUD 可用短记录推进。

## 完整演练

输入为「新增后台商品 SPU」。需求分析定位 [已有 API](../26-新增商品SPU接口.md)，复查响应、分类、状态和历史客户端，识别 POST 已交付。技术设计复用 DTO、Result、BusinessException 和事务分类校验，使用 MySQL 外键及锁；未上架创建无需新缓存或异步事件。方案审核核对后台授权、分类竞态、初始状态和已有上下架边界。实现步骤得出无需新增 API 的结论。验证步骤定位 [ProductIntegrationTests](../../shop-product/src/test/java/org/spring/shopproduct/ProductIntegrationTests.java)，并回看阶段 5 全量通过的用例；交付记录包含已有入口、复用机制、中间件结论和无需实现的原因。

验收回查六个步骤、主技能、全局规则、DoD、专项职责和引用。上述演练具备从需求到证据的完整路径，当前项目文件记录各步骤结果。
