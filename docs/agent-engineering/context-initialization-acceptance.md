# 项目上下文初始化与跨会话恢复验收

2026-11-20 · `main` 工作树，审计时基准提交 `6b3c48a`。任务按轻量 ROUTE-12 处理。

## 审计与变更

原有 README、AGENTS、Docs、七个 Skills、任务路由、业务任务记录和八阶段 Harness 均已存在。入口缺少项目状态与会话摘要，架构、数据库和业务索引未收录已实现的库存入库。已对照 reactor、Controller、Feign 调用与 Flyway 补足库存说明，历史 Java 21、RabbitMQ 和 Vue 仍保留原有适用范围。

| 交付物 | 用途 |
|---|---|
| [README](../../README.md) | 业务背景、技术基线、模块关系、本机入口和路线图 |
| [AGENTS](../../AGENTS.md) | 先读适用规则，再检查上下文与工作树。按当前任务选择路由、Docs 和 Skills |
| [PROJECT_STATUS](../../PROJECT_STATUS.md) | 当前阶段、已交付事项、证据、技术债务和风险 |
| [SESSION_HANDOFF](../../SESSION_HANDOFF.md) | 未完成任务、最近验证、阻塞条件与可恢复位置 |
| [Docs 导航](../README.md)、[架构](../architecture/README.md)、[组件](../architecture/component-catalog.md)、[数据库](../database/README.md)、[业务](../business/README.md) | 上下文入口、公共异常注册位置及库存服务事实 |
| [任务索引](tasks/README.md)、[维护](maintenance.md)、[完成定义](../standards/definition-of-done.md) | 复用任务记录、明确同步条件并接入交付 |
| [静态门禁](../../infra/quality/gate.py)和[操作说明](quality-gate.md) | 将四份根上下文文档纳入可读取性与本地链接检查 |

## 独立会话的执行边界

分别测试了本机 App Server 和隔离上下文的 Codex 验收代理。验收提示仅提供仓库位置、只读约束、十项问题和假设任务。两种途径都从空历史开始。

直接 `codex exec --ephemeral` 的首轮尝试未返回结果，随后结束该进程。本机 Codex CLI 0.169.1 的 App Server 接受 `thread/start`（`ephemeral=true`、`sandbox=read-only`、`approvalPolicy=never`），初始历史为 0，启用了只读策略。该回合在多次采样超时重试后返回答复，但对文件读取的调用均失败。错误包含 `registered Core launch requires an installed app`，因此这条途径的仓库读取验收记为 **BLOCKED**。

隔离 Codex 验收使用 `fork_turns=none`，无聊天历史，实际读取本机工作树、四份上下文文档及相应 Docs、源码、迁移和既有结果。十项检查均通过。仅设计订单时走 ROUTE-08，CRM 交接保持待办；在明确续做的对照场景，代理找到了待审文件、通过的前端检查及继续条件。[十项验收证据](evidence/context-initialization.md)

## 本地检查

| 命令 | 结果 |
|---|---|
| `python -X utf8 infra/quality/gate.py lint` | 退出 0，检查根上下文入口、文档链接、七个 Skills、构建 XML 及已存建设状态 |
| `python -X utf8 -m unittest discover -s infra/quality -v` | 退出 0，10 项通过，涵盖路由限制、漂移、证据、锁与恢复 |
| `npm.cmd --prefix shop-web run check` | 退出 0，4 项通过，使用内存 HTTP 请求和上游替身 |
| `git diff --check` | 退出 0 |

已比较 Harness 运行基线与当前 Java 输入指纹。旧基线包含 265 个输入，当前有 283 个，指纹不同。已存八阶段状态用于查阅历史证据。本轮未运行 Maven 全量、真实 Broker、OSV、Nacos 或 CRM 数据导入，当前运行状态按[项目状态](../../PROJECT_STATUS.md)记录。
