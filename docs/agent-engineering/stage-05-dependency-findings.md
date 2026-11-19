# 阶段 5 依赖扫描结果

## 升级后的实际 Maven 依赖

2026-11-19 · Maven 3.9.16 · CycloneDX Maven Plugin 2.9.1 · OSV-Scanner 2.7.1。仓库的扫描门禁改为先由 Maven 生成 reactor SBOM，再提交 OSV 匹配。这可覆盖 Maven 实际选择的传递版本，避免 manifest 解析器因仓库内 SNAPSHOT 模块而漏扫。

Spring Boot 4.0.9、Spring Cloud 2025.1.4、Alibaba 2025.1.0.0 升级后，第一次 SBOM 扫描留下 3 条告警。对照 Apache HttpClient 5.6.3 POM，将 HttpClient5 升到 5.6.3，HttpCore5 与其 HTTP/2 模块统一到 5.4.3。复测 Maven 成功并解析 271 个组件，OSV 返回 `No issues found`，退出码为 0。

复现命令为 `python -X utf8 infra/quality/gate.py dependency-scan --osv <osv-scanner>`。本机日志为 `target/harness-osv-sbom-final.log`。覆盖范围为 SBOM 记录的 Maven 构件，容器系统包和未列入 SBOM 的内嵌构件需单独检查。

## 升级前基线

2026-11-19 · OSV-Scanner 2.7.1 · `osv-scanner scan source --recursive .`

扫描了根 POM 及九个模块 POM，按公告 ID 去重得到 109 个公告，分布在 211 条模块与依赖命中行。扫描命令退出码为 1。结果来自公开依赖公告和扫描器解析，受各模块实际运行范围、配置和可达性的影响。

| 示例坐标 | 扫描版本 | 最高示例 CVSS | 公告 |
|---|---|---:|---|
| org.apache.tomcat.embed:tomcat-embed-core | 10.1.20 | 9.8 | [GHSA-5j33-cvvr-w245](https://osv.dev/GHSA-5j33-cvvr-w245) |
| org.springframework:spring-webmvc | 6.1.6 | 9.8 | [GHSA-pc63-qcmh-9cmg](https://osv.dev/GHSA-pc63-qcmh-9cmg) |
| io.netty:netty-handler | 4.1.109.Final | 9.1 | [GHSA-c4c3-7fpv-j4q5](https://osv.dev/GHSA-c4c3-7fpv-j4q5) |

升级前完整输出保存在本机 `target/harness-osv.log`。上方升级结果以 Maven 实际依赖为准。[OSV Maven 传递依赖说明](https://google.github.io/osv-scanner/supported-languages-and-lockfiles/)
