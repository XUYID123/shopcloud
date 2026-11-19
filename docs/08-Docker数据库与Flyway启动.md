# Docker MySQL 与 Flyway 自动初始化

实施日期：2026-10-15。适用当前 shop-user 的 JDK 17 / Spring Boot 3.2.5 / Flyway 10.10.0。

已完成实际验收：应用首次启动自动拉取并启动 MySQL 8.4.11，健康检查通过，V1/V2 迁移成功；Maven 聚合测试通过，用户服务集成测试验证五张 RBAC 表、角色和默认权限；再次直接运行 ShopUserApplication，复用容器，Flyway 显示无需迁移。验证 Java 进程已停止，MySQL 容器保持 healthy，映射 127.0.0.1:3307。

当前组合存在一条实际版本提示：Flyway 10.10.0 的 MySQL 检查仍以 8.1 为最新已测试版本，会对 8.4 输出 upgrade recommended。该提示未阻止本次迁移、校验和再次启动，但本次结果不能等同于上游对所有 MySQL 8.4 能力的兼容认证。现有 10.x 补丁中也有相同检查，未为消除提示而改动数据库或跨大版本升级；正式统一框架与依赖时需处理此兼容边界。[Flyway MySQL 版本检查源码](https://raw.githubusercontent.com/flyway/flyway/flyway-10.20.1/flyway-database/flyway-mysql/src/main/java/org/flywaydb/database/mysql/MySQLDatabase.java)

## 1. 启动链路

```text
Docker Desktop 引擎运行
  → IDEA 启动 ShopUserApplication
  → Spring Boot 读取 shop-user/compose.yaml
  → Docker Compose 创建或启动 MySQL 容器
  → MySQL 首次初始化 flour_user 数据库和 shop_user 账号
  → 健康检查确认应用账号可以执行 SELECT 1
  → Spring Boot 自动提供 JDBC 连接信息
  → Flyway 执行尚未执行的 V1、V2 迁移
  → 用户服务启动完成，监听 8081
```

Docker Compose 能管理容器，前提是 Docker Desktop 的引擎已经运行；启动 Java 应用不会替代 Docker Desktop 本身的启动。本配置是 IDE 本机开发用途，后续生产环境单独提供数据源并关闭 Compose 集成。

## 2. 在 IDEA 中操作

1. 打开 Docker Desktop，等待引擎就绪。
2. Maven 面板重新加载根项目，让 JDBC、Flyway 和 Compose 依赖生效。
3. 打开 ShopUserApplication 的 Run/Debug Configuration，module 选 shop-user。
4. Working directory 设置为用户模块目录：`C:/Users/xuyidong/IdeaProjects/springcloud-demo/shop-user`；当前 IDEA 运行配置已同步设置。
5. 启动 ShopUserApplication。第一次需要拉取 MySQL 镜像，耗时取决于网络与镜像缓存。
6. 查看 MySQL 健康、Flyway 迁移成功和 Started ShopUserApplication 日志。

如果选择其他工作目录，通过运行配置环境变量 SHOP_USER_COMPOSE_FILE 指定 Compose 文件的绝对路径。仅修改文件夹位置不影响已存在命名卷的数据。

## 3. 各文件负责什么

| 文件 | 职责 |
|---|---|
| shop-user/compose.yaml | 用户服务自己管理 MySQL：固定镜像、本机端口、数据库账号、健康检查和持久化数据卷 |
| shop-user/src/main/resources/application.yml | 启用 Compose、指定文件、连接池和 Flyway 扫描规则 |
| db/migration/V1__create_rbac_tables.sql | 创建用户、角色、权限及两张关联表 |
| db/migration/V2__seed_roles_and_permissions.sql | 初始化 USER、ADMIN 两个角色、七个权限及角色权限关联 |

SQL 路径相对于用户服务的 src/main/resources。原 db/migration 是一个空文件，已保留为 db/migration.placeholder，再创建真正的 migration 目录。原来放在 db 直接下的空 V1 文件已由有效迁移替代。

common 中保留公共响应和异常处理；应用名和 Compose 开关归业务服务管理，避免 common 的 application.properties 覆盖用户服务 YAML。common 旧的 compose.yaml 不会被本配置使用。

## 4. 从 IDEA 数据库面板连接 MySQL

在 Database 面板创建 MySQL 数据源：

| 项目 | 默认值 |
|---|---|
| Host | 127.0.0.1 |
| Port | 3307 |
| Database | flour_user |
| User | shop_user |
| Password | shop_user_local_dev |
| JDBC URL | jdbc:mysql://127.0.0.1:3307/flour_user |

若客户端提示公钥获取被拒绝，本机开发数据源的 Driver properties 可设置 allowPublicKeyRetrieval=true、useSSL=false；这些选项只用于本机开发连接。Java 应用使用 Compose 的连接详情，无需重复维护 URL、用户名与密码。

Compose 默认口令仅用于本机开发，数据库端口只绑定 127.0.0.1。可在 IDEA 运行配置环境变量中覆盖：

| 环境变量 | 用途 |
|---|---|
| SHOP_USER_MYSQL_PASSWORD | MySQL 应用账号口令 |
| SHOP_USER_MYSQL_ROOT_PASSWORD | MySQL 初始化 root 口令 |
| SHOP_USER_MYSQL_PORT | 本机映射端口，默认 3307 |
| SHOP_USER_COMPOSE_FILE | Compose 路径，默认用户模块目录中的 compose.yaml |

Compose 会使用启动 Java 进程继承的环境变量，连接详情也从容器中解析。已有数据卷的数据库账号不会因修改 MYSQL_PASSWORD 等初始化环境变量而自动修改；修改现有账号口令时需同步更新数据库账号和运行配置。

## 5. Flyway 的行为

- MySQL 镜像负责首次创建数据库；Flyway 负责数据库中的表及版本数据。
- V1 创建 user_account、role、permission、user_role、role_permission，含唯一约束、外键与状态约束。
- V2 初始化角色和权限，不创建管理员账号，也不预置用户密码。注册功能后续在一个本地事务中创建用户并关联 USER。
- Flyway 自动创建 flyway_schema_history，记录迁移版本与校验和。重复启动会校验并跳过已经成功的迁移。
- baseline-on-migrate=false，防止对意外的非空旧库自动建立基线而跳过 V1；这里使用独立的新数据库。
- 已执行的迁移保持不变，后续变更新增 V3__xxx.sql、V4__xxx.sql。
- clean-disabled=true，不让 Flyway 清库。

执行迁移失败时应用启动失败，先处理具体 SQL 或连接错误，不关闭 Flyway 来掩盖失败。

## 6. 停止与再次启动

配置 lifecycle-management=start-only：停止 Java 应用时保留 MySQL 容器运行，数据保存在命名卷中。容器被停止后，再启动应用会重新启动它；引擎需要处于运行状态。已经运行的容器会被复用。

如果明确希望 Java 退出时也停止本次启动的容器，可以改为 start-and-stop；Compose 停止容器仍保留卷中数据。Spring Boot 对启动前已运行的服务有复用规则，不能把该设置理解成始终独占和关闭共享数据库。

## 7. 验收

通过 SQL 验证：

```sql
SHOW TABLES;
SELECT installed_rank, version, description, success
FROM flyway_schema_history ORDER BY installed_rank;
SELECT role_code, role_name FROM `role`;
SELECT permission_code, permission_name FROM permission;
```

预期五张 RBAC 表和一张迁移历史表，版本 1/2 成功，两个角色、七个权限。再次启动后，迁移历史不新增重复 V1/V2，角色和权限不重复。

shop-user 的 Spring Boot 集成测试启用真实 Compose，验证 Flyway、五张表和默认用户权限；执行测试前同样需要 Docker。Maven 测试的工作目录已设置为 shop-user 模块目录。

依据：[Spring Boot Docker Compose 支持](https://docs.spring.io/spring-boot/docs/3.2.5/reference/html/features.html#features.docker-compose)、[Spring Boot Flyway 初始化](https://docs.spring.io/spring-boot/docs/3.2.5/reference/html/howto.html#howto.data-initialization.migration-tool.flyway)、[MySQL 官方镜像版本清单](https://raw.githubusercontent.com/docker-library/official-images/master/library/mysql)。
