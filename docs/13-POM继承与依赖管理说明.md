# POM 继承与依赖管理说明

调整日期：2026-10-18。按最终选定的范围：业务服务默认继承 MyBatis、MySQL、Lombok、Flyway；gateway 和 common 隔离数据库依赖，同时保留 dependencyManagement 的按需引入模式。

框架版本保持 Java 17、Spring Boot 3.2.5、Spring Cloud 2023.0.1、Spring Cloud Alibaba 2023.0.1.0。

## 1. 当前继承结构

```text
spring-boot-starter-parent:3.2.5
└── shop-base-parent:1.0-SNAPSHOT（基础版本、插件、Lombok、测试）
    ├── shop-common:1.0-SNAPSHOT（公共库，不带数据库依赖）
    ├── shop-gateway:1.0-SNAPSHOT（网关，不带数据库依赖）
    └── shop:1.0-SNAPSHOT（根 POM，默认增加数据库依赖）
        ├── shop-user
        ├── shop-product
        ├── shop-cart
        ├── shop-order
        └── shop-pay
```

根目录 [pom.xml](../pom.xml) 仍然聚合整个工程：七个代码模块加上一个基础父 POM，共八个 module。Maven 的 modules 控制一起构建哪些项目，parent 控制继承谁；common/gateway 虽不继承根 POM，仍参加根项目的构建。

已有坐标保留：common/user/gateway 使用 com.dong、1.0-SNAPSHOT；另外四个业务服务仍使用 org.spring、0.0.1-SNAPSHOT。新增基础父 POM 为 com.dong:shop-base-parent:1.0-SNAPSHOT。Nacos 注册名来自应用配置，不由 Maven 坐标决定。

## 2. 为什么多了一个基础父 POM

Maven 子模块不能通过普通 exclusions 移除从 parent 直接继承的依赖。exclusions 只影响某条依赖路径上的传递依赖；optional=true 也不会阻止父依赖传给继承它的子模块。

所以用两条继承路径实现隔离：

| 模块 | parent | 自动继承的主要依赖 |
|---|---|---|
| user/product/cart/order/pay | shop | MyBatis/JDBC、MySQL、Flyway core、Flyway MySQL、Lombok、测试 |
| gateway/common | shop-base-parent | Lombok、测试；没有默认数据库依赖 |

[shop-base-parent/pom.xml](../shop-base-parent/pom.xml) 统一提供 Boot parent、Cloud/Alibaba BOM、版本属性、common 版本和构建插件约定。

根 POM 继承基础层，进一步声明数据库依赖和对应版本管理；因此继承根 POM 的业务服务默认就有数据库技术栈。

## 3. 两种依赖模式同时保留

| 配置位置 | 是否自动引入依赖 | 当前职责 |
|---|---|---|
| 父 POM 的 dependencies | 是 | 声明默认依赖，由继承该父级的子模块自动获得 |
| 父 POM 的 dependencyManagement | 否 | 约束依赖版本；本身不把库加入 classpath |
| 子 POM 的 dependencies | 是 | 引入本模块需要的其他组件，并使用父级管理的版本 |

### 3.1 根 POM 默认提供的数据库依赖

```xml
<dependencies>
    <dependency>
        <groupId>org.mybatis.spring.boot</groupId>
        <artifactId>mybatis-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-mysql</artifactId>
    </dependency>
</dependencies>
```

| 默认依赖 | 当前版本 | 执行意义 |
|---|---|---|
| MyBatis starter | 3.0.3 | 提供 Mapper 集成，并传递引入 JDBC starter、连接池等依赖 |
| MySQL Connector/J | 8.3.0 | 运行时连接 MySQL 的 JDBC 驱动 |
| Flyway core | 10.10.0 | 数据库迁移核心 |
| Flyway MySQL | 10.10.0 | 当前 Flyway 版本所需的 MySQL 支持扩展 |
| Lombok（来自基础父 POM） | 1.18.32 | 编译阶段生成 getter/setter 等代码 |
| Boot test（来自基础父 POM） | 3.2.5 | 测试作用域的 Spring Boot、JUnit 等支持 |

user 已删除重复的 MyBatis/JDBC、MySQL 和 Flyway 声明，不再为这些依赖单独维护一份配置。其数据库连接、Compose 和迁移脚本仍由 user 自己拥有。

Lombok 的 optional=true 用于阻止它向引用模块的其他项目传递，不用于控制父子继承。Boot 插件还统一将 Lombok 排除在运行 JAR 外；测试依赖采用 test 作用域，也不进入运行包。

### 3.2 保留按需引入模式

Web、Nacos、Sentinel、Redis、校验、密码编码等组件仍由需要的子模块声明。Boot/Cloud/Alibaba 管理它们的版本，不会自动引入全部组件。

例如业务服务需要 Nacos 时，在自己的 dependencies 中声明：

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```

不需要写 version，因为 Alibaba BOM 已管理版本。没有声明且没有其他依赖传递引入时，不会仅因导入 BOM 就获得该组件。

当前数据库依赖同时出现在根 POM 的 dependencies 与 dependencyManagement 中，两者职责不同：

- dependencies 决定默认引入。
- dependencyManagement 决定所用版本。

如果以后希望某个库只按需引入，保留其 dependencyManagement 条目，并移除父 dependencies 中的默认声明，再在需要它的子模块中声明。

## 4. 网关与 common 如何隔离

### 4.1 gateway

gateway 的 parent 指向 shop-base-parent，不经过根 POM 的数据库依赖层，因此它的 classpath 不会默认包含 MyBatis、JDBC、MySQL、Flyway。

它继续自己声明 Gateway/WebFlux、响应式 Redis、校验、Compose、LoadBalancer、Nacos、Sentinel 和网关适配器。

网关引用 common 时仍排除 common 的 MVC starter，避免 WebFlux 与 MVC 运行栈混入同一网关。common 已移除 MySQL 驱动，因此对应的旧 MySQL exclusion 一并移除。

### 4.2 common

common 同样继承 shop-base-parent，移除了数据库驱动和 Compose 依赖。数据库与容器配置归各业务服务管理，用户服务和网关已有自己的 Compose 依赖。

common 仍保留当前源码及其所需的 Web 依赖，不在本次重构中全面改写统一响应、异常处理或演示代码。它产出普通 JAR，不启用 Boot repackage，也不引入 Nacos 自动注册。

common 的引用版本由基础父 POM 的 shop-common.version=1.0-SNAPSHOT 管理，user/gateway 不需要重复填写。没有直接套用 ${project.version}，因为另外四个服务的版本是 0.0.1-SNAPSHOT，未来引用 common 时仍应解析到 common 自己的版本。

## 5. 四个骨架服务为何暂时关闭数据库自动装配

product/cart/order/pay 已默认获得数据库依赖，但当前还没有各自的数据源配置。只有依赖并不代表已经连接数据库；不做处理会在启动时尝试创建数据源并因缺少连接信息失败。

所以在这四个模块原有的 application.properties 中暂时增加：

```properties
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration
```

| 排除项 | 暂时关闭什么 |
|---|---|
| DataSourceAutoConfiguration | 不自动创建缺少连接信息的数据源 |
| FlywayAutoConfiguration | 不在这些尚未接库的骨架服务上自动执行迁移 |
| MybatisAutoConfiguration | 不自动装配尚未使用的 MyBatis 数据库组件 |

这四个服务仍然实际继承数据库依赖，不是把它们从依赖树中排除；当前只停用对应的自动装配。Nacos 注册与 HTTP 服务继续工作。

后续为某个服务接入数据库时：

1. 准备该服务自己的数据库，配置它自己的连接与凭据；需要本地容器时单独启用 Compose 支持。
2. 配置该服务的 Flyway 脚本位置，添加它自己的迁移脚本。
3. 在该服务中添加 Entity、Mapper、SQL 以及相应的 Mapper 扫描。
4. 删除这个服务的上述三项排除配置，使数据库、Flyway、MyBatis 自动装配恢复。
5. 启动并验证连接、迁移和业务 SQL；不能直接复用 user 的数据库配置和用户表迁移。

user 没有添加这些排除项，保持现有数据源、Flyway 和 MyBatis 的工作方式。

## 6. 版本与构建插件在哪里调整

| 内容 | 配置位置 |
|---|---|
| Java、Boot、Cloud、Alibaba、MyBatis、MySQL、Flyway 等版本属性 | shop-base-parent/pom.xml 的 properties |
| Boot parent 的版本 | shop-base-parent/pom.xml 的 parent |
| common 版本、Cloud/Alibaba BOM、Lombok 版本管理 | shop-base-parent/pom.xml 的 dependencyManagement |
| 默认数据库依赖与其版本管理 | 根 pom.xml |
| Boot 打包插件、Surefire 版本、Lombok 打包排除 | 基础父 POM 的 pluginManagement |
| user 的测试 workingDirectory | user/pom.xml，保留为 ${project.basedir} |

升级 Boot 时同步调整基础父 POM 的 Boot parent 版本与 spring-boot.version，并核对 Cloud/Alibaba 的兼容关系。业务子 POM 不再独立维护这些版本。

六个服务声明 Boot 插件后生成可执行 JAR；common 不声明该插件，保持普通依赖 JAR。基础父 POM 和根 POM 都是 packaging=pom，不生成应用 JAR。

## 7. IDEA 中如何生效

1. 重新加载根项目的 Maven，确认新增的 shop-base-parent 也被导入。
2. 在根 POM 看默认数据库依赖；在基础父 POM 看版本、Lombok、测试和插件。
3. user/product/cart/order/pay 的 parent.relativePath 为 ../pom.xml。
4. common/gateway 的 parent.relativePath 为 ../shop-base-parent/pom.xml；根 POM 自己通过 shop-base-parent/pom.xml 找到基础父 POM。
5. 按原有运行配置启动服务；user/gateway 的 Compose 文件和工作目录仍属于各自模块。
6. 要看最终继承结果，可使用 Maven Help 插件的 help:effective-pom 或依赖插件的 dependency:tree。

不要仅因为子 POM 没有写 MyBatis 或 Flyway，就判断它缺少依赖；需要查看其继承链和最终 classpath。

## 8. 验证记录

- 本次最终结构的全工程 package -DskipTests 已通过：七个代码模块的主代码和测试代码均完成编译，新增基础父 POM 也参加构建。
- 检查产物：五个业务服务均包含 MyBatis 3.0.3、MySQL 8.3.0、Flyway core/MySQL 10.10.0；网关不含数据库和 MVC 依赖；Lombok、测试依赖不进入服务运行包。
- common 使用无数据库依赖的基础继承路径，并保持普通 JAR。
- 最终结构下 16 项回归测试通过：common 启动 1 项，网关 IP/路由/Redis 不可用/Sentinel 容量/启动 11 项，product/cart/order/pay 启动各 1 项。测试期间关闭真实 Nacos 注册，应用的正式注册配置不变。
- 本次未重跑用户数据库集成测试和真实 Redis 防刷并发测试，不将它们计入通过项；user 的主代码及数据库测试代码已完成编译。
