# 建设检查点

当前建设状态以 [progress.json](progress.json) 和实际证据为准。查看[本轮升级验收](upgrade-acceptance-report.md)，或使用[恢复命令](construction-workflow.md)核对已通过阶段并从失配位置继续。此页下方保留 **2026-11-19 原八阶段验收**，详见[历史验收报告](final-acceptance-report.md)。

阶段约定见[阶段契约](stages.json)和[原任务书](../../Java%20Agent%20Engineering%20Harness%20建设任务书.md)。日常任务从[任务路由](workflows/task-routing.md)进入并使用独立记录。

## 历史验收

- `STAGE_1_STATUS = PASS` · [stage-01-audit.md](stage-01-audit.md) · 已对照模块、组件、迁移、Redis、消息状态和工程风险。
- `STAGE_2_STATUS = PASS` · [stage-02-knowledge-base.md](stage-02-knowledge-base.md) · 知识库、组件目录、规则和 ADR 已落盘，18 个 Markdown 文件的链接通过检查。
- `STAGE_3_STATUS = PASS` · [stage-03-agents.md](stage-03-agents.md) · 新增商品演练已定位现有 API、事务、鉴权和组件。
- `STAGE_4_STATUS = PASS` · [stage-04-skills.md](stage-04-skills.md) · 本机 Codex 实际发现七个技能，三个场景的技能路由已走查。
- `STAGE_5_STATUS = PASS` · [stage-05-quality.md](stage-05-quality.md) · 快速 134 项、隔离全量 274 项全通过。真实 RocketMQ 重试、幂等和 OSV 扫描通过。
- `STAGE_6_STATUS = PASS` · [stage-06-lifecycle.md](stage-06-lifecycle.md) · AGENTS 和主技能已接入六步流程，Codex 重新发现 7/7 个技能，商品场景已走通。
- `STAGE_7_STATUS = PASS` · [stage-07-scenarios.md](stage-07-scenarios.md) · 四个业务场景通过设计验收，103/108 分，未触发否决条件。
- `STAGE_8_STATUS = PASS` · [stage-08-maintenance.md](stage-08-maintenance.md) · 维护版本与 ADR、回归机制通过重复消费演练，自检引用和真实测试证据有效。

- `FINAL_ACCEPTANCE_STATUS = PASS` · 快速 134 项与隔离全量 274 项通过，OSV 无命中，四个场景得分 95.4/100。
