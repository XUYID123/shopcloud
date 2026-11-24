# 工程建设与恢复

**工作流 A**用于首次建设或整合 Harness 的全量升级。现有八阶段交付物沿用。[阶段契约](stages.json)逐项保存目标、输入、前置依赖、执行、产物、验收、检查、失败和退出条件。**工作流 B**以及故障、审查等其他日常任务通过[任务路由](workflows/task-routing.md)选择。小范围 Harness 维护走 ROUTE-12 的轻量分支。

## 执行入口

从仓库根目录运行以下命令。仓库已提供 [progress.json](progress.json)。

| 命令 | 行为 |
|---|---|
| `python -X utf8 infra/quality/workflow.py init` | 新建建设状态，仅用于首次初始化 |
| `python -X utf8 infra/quality/workflow.py validate` | 只读校验 JSON 结构、顺序和当前记录的证据完整性。供本地门禁、Hook 和 CI 使用 |
| `python -X utf8 infra/quality/workflow.py start` | 重验前置阶段并记录当前执行位置，供 Agent 实施当前阶段 |
| `python -X utf8 infra/quality/workflow.py run` | 复查实际产物和前置证据，运行已注册的验收，验证通过后自动推进，失败返回非零 |
| `python -X utf8 infra/quality/workflow.py run --through 4` | 验收到指定边界。Agent 需要先完成该阶段契约中的产物 |
| `python -X utf8 infra/quality/workflow.py reopen 4 --reason "发现条件已恢复"` | 有依据地重开该阶段和后继阶段，保留之前的验收文件 |

Agent 先读取状态，确认当前阶段，按契约交付。人工语义复核写入本阶段报告，报告中用 `[stage-NN]: ACCEPTED` 标记复核完成并附理由。脚本将其记为 AGENT，并实际执行阶段的 TOOL 检查。脚本负责验收推进和恢复，缺失产物由当前 Agent 按契约补齐。完整建设必须同时具有测试证据与语义审查。

## 状态与恢复

状态为 PENDING、IN_PROGRESS、VERIFYING、PASS、FAILED 或 BLOCKED。记录的 UTC 时间属于本轮工具验收。旧报告保留原验收日期。current_stage 始终是首个未 PASS 阶段，全部完成时为 null。状态文件使用独占进程锁，先写新证据，再原子替换状态。

中断后直接重新运行 run。它检查前置 PASS 的证据摘要、文件和阶段契约，重查本地引用及阶段 5 的运行范围。出现漂移时从最早失配阶段重验，后继回到 PENDING，旧证据保留。卡在 IN_PROGRESS 或 VERIFYING 时从当前阶段重跑验收。直接产物已变而复核说明仍旧时，脚本记录 NOT_VERIFIED，Agent 按当前产物重新检查并更新说明。

首次失败保存失败原因和证据。修复后重新运行，retry_count 记录已经启动的重试次数。同因连续三次失败进入 BLOCKED。只有条件已恢复、所需授权明确或可验证新信息足以改变结论时才 reopen。权限边界以工具环境为准；所有破坏性、生产和重大决策操作按项目约束确认。

证据读法、发现和运行复验见[证据目录](evidence/README.md)。复杂业务工作使用[独立任务记录](tasks/README.md)。
