# 本地与 CI 质量门禁

要求 Python 3.10+、Git、Java 17 和 Maven 3.9+。完整测试需要 Docker Compose v2 和可访问的本地 Docker socket。

- 静态检查执行 `python -X utf8 infra/quality/gate.py lint`。检查根目录四份上下文文档是否可读、仓库本地链接、Skills、POM/Mapper XML 和建设记录。事实同步按[文档维护规则](maintenance.md#项目上下文维护)复核。
- 快速检查：`python -X utf8 infra/quality/gate.py quick`。执行 Git whitespace 检查、POM/Mapper XML/文档/技能结构及 [建设状态](progress.json)校验，再运行无需 MySQL、Redis 或 RocketMQ 的 Maven verify。路由及恢复回归使用 `python -X utf8 -m unittest discover -s infra/quality -v`，CI 快速 job 也运行该组测试。
- 完整检查：`python -X utf8 infra/quality/gate.py full`。启动随机命名的隔离 Compose 栈，两套 MySQL、两套 Redis 和 RocketMQ gRPC Proxy 通过 127.0.0.1 随机端口连接。NameServer 与 Broker 管理端口留在 Compose 网络中。脚本提供独立 Topic、消费组和消息测试 schema，结束时移除该栈及其卷。支持 `--docker-context desktop-linux`。默认拉取 RocketMQ 5.5.0，可用 `--rocketmq-image` 选择本机已验证的同系列测试镜像。
- 使用已有 Maven 或离线缓存时，传 `--maven <mvn 路径> --maven-repo <仓库目录> --offline`。测试采用自动装配与现有 JUnit 5/Mockito/Surefire。报告在各模块 target/surefire-reports，脚本检查新报告、失败和跳过。
- 秘密扫描：`python -X utf8 infra/quality/gate.py secret-scan --gitleaks <可执行文件>`。快照覆盖 Git 索引和未忽略文件，输出全脱敏。CI 还扫描 Git 历史。测试私钥例外绑定公开测试夹具及固定内容摘要。
- 依赖安全：`python -X utf8 infra/quality/gate.py dependency-scan --osv <可执行文件>`。Maven 解析 reactor 的直接和传递依赖，生成 `target/harness.cdx.json`，OSV 检查该 SBOM。运行需网络，告警和工具错误均阻断 CI。
- 可选安装提交检查：`git config --local core.hooksPath .githooks`。安装前核对已有 hooks 路径。脚本在提交时校验索引空白格式及当前工作树结构。
- [GitHub Actions](../../.github/workflows/quality.yml)在 push/PR 执行 quick、integration、security。仓库推送后将三个 job 设置为受保护分支的 required checks，由仓库管理权限完成。首个提交前可在本机运行同样命令。

RocketMQ 验收入口为 [真实投递和回滚重试用例](../../shop-messaging/src/test/java/org/spring/shopmessaging/RocketMqIntegrationTests.java)。FIFO 顺序、业务积压和死信重放随实际消息用例补齐。验收依据见[消息流程](../messaging/README.md)和[一致性](../messaging/database-consistency.md)。
