# Agent Engineering Harness 2.0 与任务路由验收

2026-11-20 · 建设八阶段全部 PASS。日常任务采用独立的六步开发记录，路由规则覆盖十二种任务类型。各阶段的自动检查、人工语义复核、失败和恢复过程已写入仓库。[建设状态](progress.json) · [阶段契约](stages.json) · [证据目录](evidence/README.md)

## 交付结果

| 范围 | 验收结果 |
| --- | --- |
| 审计、知识库、AGENTS | 已对照 Maven 模块、现有组件和 ADR。AGENTS 按任务意图路由，文档索引明确现行资料与历史记录。 |
| Skills、质量闸门 | 实际发现七个仓库技能。静态闸门验证状态与证据结构，CI 快速任务增加工作流和路由回归。 |
| 日常流程、场景、维护 | 商品新增和异步订单分别完成六步演练。商品复用现有接口，订单完成技术设计并记录实施前的业务决策。维护约定覆盖版本、阶段重开及证据保留。 |
| 失败与恢复 | 独立副本故意缺少阶段一产物，验收退出 1 且阶段二保持 PENDING。修复后一次运行推进两个阶段。阶段三由另一进程恢复并通过，保留首次失败证据。 |
| 任务路由 | 十二个指定样本和八个补充样本均符合预期，另核对混合请求、风险升级和显式只读范围。Agent 判断自然语言意图，策略脚本校验结构化意图、工作流和写入边界。 |

[日常演练](tasks/upgrade-rehearsal.md) · [恢复演练原始结果](evidence/construction-rehearsal.json) · [路由规则](workflows/task-routing.md) · [路由验收矩阵](evidence/router-validation.md)

## 本轮执行证据

| 检查 | 结果 |
| --- | --- |
| `python -X utf8 infra/quality/workflow.py run` | 退出 0，按阶段顺序验收，状态和检查详情已落盘。 |
| `python -X utf8 infra/quality/workflow.py validate` | 退出 0，验证建设状态、产物与证据指纹。 |
| `python -X utf8 -m unittest discover -s infra/quality -v` | 退出 0，10 项测试覆盖恢复、漂移、证据篡改、锁、重试上限和路由策略。 |
| `python -X utf8 infra/quality/gate.py lint` | 退出 0，文档链接、技能、XML、CI 配置及状态门禁通过。 |
| Codex app-server `skills/list` | 实际返回七个已启用仓库技能，工作区和路径已核对。 |
| 本机 Git pre-commit、actionlint、Gitleaks | 均通过。 |

[Codex 实际发现结果](evidence/skill-discovery.json) · [建设执行与恢复命令](construction-workflow.md)

## 验收范围

本轮改动集中在 Harness、工程文档及质量闸门。对 265 个应用输入核验指纹后，复用 2026-11-19 的运行基线。基线包含 134 个快速测试和 274 个隔离全量测试，含真实 RocketMQ 用例，OSV 扫描无命中。本轮应用测试为 NOT_RUN，托管 CI 为 NOT_RUN，后续会话中根据自然语言自动选择路由或隐式触发技能为 NOT_VERIFIED。业务演练的运行状态逐项记在任务文件中。[应用运行基线](evidence/runtime-baseline.json)
