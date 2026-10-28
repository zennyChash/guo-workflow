# guo-workflow

基于 Flowable 7 的业务动作扩展引擎。它通过节点绑定的 XML 声明同步校验、业务
SQL、脚本、回退对冲和异步任务，将流程流转与业务执行解耦。

## 核心特性

- **契约先行**：动作、生命周期事件、执行上下文和异步指令都有独立契约。
- **分层扩展**：Trigger、Dispatcher、Executor 职责分离，执行器按类型自动注册。
- **同步失败回滚**：同步动作抛出 `FlowableActionException`，由流程事务统一回滚。
- **异步任务闭环**：完整指令持久化到 `t_async_task`，消费者轮询、执行并回写状态。
- **数据库无关**：核心逻辑 100% 使用 `JdbcTemplate` 和 `java.sql`，不使用 ORM。
- **无方言 SQL**：消费者通过 JDBC `setMaxRows` 限制拉取条数，不拼接 `LIMIT` 或
  `ROWNUM`。
- **可替换执行器**：SQL、脚本、消息等具体动作通过 `BusinessAction` 扩展。

## 模块

| 模块 | 职责 |
| --- | --- |
| `workflow-contract` | 动作定义、执行上下文、指令和异常契约 |
| `workflow-core` | 动作解析与路由、异步链路、执行器、数据源适配、配置仓储和流程服务 |
| `workflow-boot` | Spring Boot 启动、调度、Web 异常兜底和数据库驱动 |

依赖方向为 `workflow-boot -> workflow-core -> workflow-contract`。

`workflow-core` 的包按职责划分，避免把异步基础设施和配置访问混入执行器：

| 包 | 职责 |
| --- | --- |
| `com.guo.workflow.dispatcher` | XML 解析、动作注册、路由和同步/异步分发 |
| `com.guo.workflow.executor` | `BusinessAction` 实现及 SQL、脚本执行辅助代码 |
| `com.guo.workflow.async` | 异步指令发布、抢占、消费、重试和死信处理 |
| `com.guo.workflow.datasource` | 业务数据源解析，隔离 Flowable 主库 |
| `com.guo.workflow.repository` | 流程和节点动作配置的 JDBC 仓储 |
| `com.guo.workflow.trigger` | Flowable 生命周期事件接入 |
| `com.guo.workflow.service` | 面向调用方的流程引擎服务 |

## 数据库驱动说明

框架默认支持 MySQL（已内置）。核心逻辑无 ORM 依赖，100% 标准 JDBC。如需在生产环境连接 Oracle 或达梦，请自行在宿主项目中引入对应版本的 JDBC 驱动。

Oracle 和达梦驱动在当前工程中只作为本地开发测试依赖。达梦驱动可在本地放置为
`workflow-boot/lib/DmJdbcDriver18.jar`，测试时使用 `-Pdm-jdbc-driver` 显式启用；
该 JAR 不会提交或打入生产包。

## 异步链路

1. Dispatcher 将异步动作组装为完整的 `InstructionMsg`。
2. `JdbcInstructionPublisher` 将指令序列化为 JSON 并写入 `t_async_task`。
3. `AsyncTaskConsumer` 每 5 秒拉取 `PENDING` 任务。
4. Registry 按 `ActionType + ActionDoType` 解析执行器，消费者执行完整的动作定义。
5. 成功后状态更新为 `SUCCESS`；失败后进入 `RETRY_WAIT` 并按指数退避重试；
   超过最大重试次数后进入 `DEAD`。

## 高可靠异步消费

消费者采用数据库条件更新完成抢占，每个任务通过 `claim_token` 防止旧 worker
覆盖新执行结果。调度器只负责抢占和投递，有界 `ThreadPoolTaskExecutor` 负责执行。

状态机：

```text
PENDING -> PROCESSING -> SUCCESS
                    \-> RETRY_WAIT -> PROCESSING
                    \-> DEAD
```

超时未完成的 `PROCESSING` 任务会恢复为 `PENDING`。该机制提供 at-least-once
语义，具体业务动作必须使用业务主键保持幂等。`tenant_id` 已预留，但当前查询
暂不按租户过滤。

已有数据库升级时执行：

```text
workflow-boot/src/main/resources/db/mysql/migrate_async_task_v2.sql
workflow-boot/src/main/resources/db/oracle/migrate_async_task_v2.sql
```

## SQL 数据源

SQL 动作的 `dataSource` 属性必须对应 Spring 中同名的业务 `DataSource` Bean，
例如 `dataSource="biz_db"` 对应 Bean 名称 `biz_db`。框架会拒绝使用 Flowable
主数据源 Bean `dataSource`，业务 SQL 不存在回退到主库的行为。

SQL 中的 `#{name}` 会转换为 JDBC `?` 参数并交给 `PreparedStatement` 绑定。
JOB 使用独立连接手动提交或回滚，VALIDATE 只执行只读查询；结果行数限制使用
JDBC `setMaxRows`，不拼接受数据库方言影响的分页 SQL。

## 快速开始

1. 创建业务数据库，手动执行对应建表脚本：

```text
workflow-boot/src/main/resources/db/mysql/schema.sql
workflow-boot/src/main/resources/db/oracle/schema.sql
```

2. 复制本地配置模板，并通过环境变量设置数据库连接：

```powershell
Copy-Item workflow-boot/src/main/resources/application-local.example.yml `
  workflow-boot/src/main/resources/application-local.yml
```

真实的 `application-local.yml` 已被 Git 忽略，不会进入版本库。

3. 使用 JDK 17 构建并启动：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
mvn clean verify
mvn -pl workflow-boot -am spring-boot:run
```

如需验证达梦驱动，先将对应 JAR 放到指定目录，再执行：

```powershell
mvn -Pdm-jdbc-driver -pl workflow-boot -am test
```

4. 健康检查：

```text
GET http://localhost:8080/actuator/health
```

单次消费拉取条数通过 `guo.workflow.fetch-size` 配置，默认值为 `10`。

## 当前执行器

- `MessageAction`：解析 `<target>` 和 `<template>`，在控制台输出模拟消息。
- `ScriptJobAction`、`ScriptValidateAction`：执行 Groovy 或 JavaScript 脚本。
- `SqlJobAction`、`SqlValidateAction`：执行业务库 SQL 任务和只读校验。
- `StoredProcJobAction`：按 `<proc>` 参数顺序调用 MySQL/Oracle 存储过程，
  支持 ResultSet、标量 OUT 和 CURSOR OUT。
- 其他导出执行器仍由 `BusinessAction` 接口扩展。

