# 验收证据

- **PASS** 已实际验证通过。**FAIL** 已实际验证失败。**NOT_RUN** 没有运行。**NOT_VERIFIED** 缺少证据。**NOT_APPLICABLE** 当前范围不适用。
- TOOL 记录包含检查范围、命令（如有）、退出码（如有）、摘要、文件和本地日志位置。AGENT 记录用于人工演练、语义审查及设计判断。纯文件存在检查只证明产物可读取。
- `construction/` 保存每次阶段尝试的独立 JSON。progress.json 持有当前引用与摘要。修复前失败或重新验收时的旧证据保留，可用 stage 与 recorded_at 查找。
- [技能发现](skill-discovery.json)记录 App Server 的实际路径匹配和启用结果。未来自动触发标为 NOT_VERIFIED。重验使用 `python -X utf8 infra/quality/workflow_checks.py discover --codex <Codex CLI>`，由当前工具权限决定能否运行。
- [运行基线](runtime-baseline.json)保留隔离 Maven、RocketMQ 和 OSV 的实际旧结果及输入摘要。源码或依赖变化时，使用 `python -X utf8 infra/quality/workflow_checks.py runtime --help` 查看重新运行方法。门禁和 CI 配置另有本轮实际校验。工作区日志保存在忽略的 target，证据 JSON 保留可随仓库迁移的摘要。
- [任务路由矩阵](router-validation.md)和[结构化样本](router-cases.json)记录自然语言演练和边界回归。
- [独立 CLI 演练](construction-rehearsal.json)记录预期失败退出码、修复后自动推进和跨进程恢复。
- [跨会话初始化](context-initialization.md)记录零历史验收的文件定位、任务恢复边界及本机 App Server 的对照结果。
