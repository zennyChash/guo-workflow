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
| `com.guo.workflow.message` | 渠道无关的消息请求和 `MessageSender` 插件接口 |
| `com.guo.workflow.export` | 文件导出请求和对接导出模块的统一 `FileExportGateway` 接口 |
| `com.guo.workflow.business` | 宿主业务动作请求、结果、Handler 和 Gateway 契约 |
| `com.guo.workflow.async` | 异步指令发布、抢占、消费、重试和死信处理 |
| `com.guo.workflow.mirror` | Flowable 生命周期事件的业务侧镜像、轨迹和只读查询 |
| `com.guo.workflow.datasource` | 业务数据源解析，隔离 Flowable 主库 |
| `com.guo.workflow.repository` | 流程和节点动作配置的 JDBC 仓储 |
| `com.guo.workflow.trigger` | Flowable 生命周期事件接入 |
| `com.guo.workflow.task` | 面向宿主的待办、候选组解析与任务生命周期门面 |
| `com.guo.workflow.service` | 面向调用方的流程引擎服务 |

## 宿主集成边界

宿主业务代码建议只依赖以下稳定的业务侧接口：

| 接口 | 职责 |
| --- | --- |
| `WorkflowEngineService` | 带租户和操作人发起流程 |
| `WorkflowTaskService` | 待办查询、领取、退回领取、转办和完成 |
| `WorkflowOperationService` | 受约束退回和业务取消 |
| `ApprovalProcessManagementService` | 定义预览、发布和版本查询 |
| `WorkflowQueryService` | 流程镜像、任务、轨迹和业务关联查询 |

具体动作通过 `BusinessAction`、`BusinessOperationHandler`、`MessageSender`、
`FileExportGateway` 和 `WorkflowGroupResolver` 等 SPI 扩展。默认不对外暴露 REST
接口；内嵌模式下由宿主 Controller 调用上述 Bean。后续改为微服务时，可在 HTTP
适配层复用同一组业务接口，不改变 Flowable 和 action 核心链路。

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
workflow-boot/src/main/resources/db/mysql/migrate_async_task_v3.sql
workflow-boot/src/main/resources/db/oracle/migrate_async_task_v3.sql
workflow-boot/src/main/resources/db/mysql/migrate_async_task_v4.sql
workflow-boot/src/main/resources/db/oracle/migrate_async_task_v4.sql
workflow-boot/src/main/resources/db/mysql/migrate_action_scope_v1.sql
workflow-boot/src/main/resources/db/oracle/migrate_action_scope_v1.sql
workflow-boot/src/main/resources/db/mysql/migrate_process_mirror_v1.sql
workflow-boot/src/main/resources/db/oracle/migrate_process_mirror_v1.sql
```

全新环境直接执行对应数据库的 `schema.sql`。已有环境按当前版本补齐缺失的 migration，
执行前应先备份并人工核对。框架不自动执行建表或升级，避免应用启动时误改生产库。

## SQL 数据源

SQL 和存储过程动作通过 `dataSource` 属性引用逻辑数据源名称。解析顺序固定为：

1. 宿主 Spring 容器中同名的业务 `DataSource` Bean；
2. 引擎 `guo.workflow.datasources` 配置创建的连接池；
3. 找不到时抛出异常。

因此宿主 Bean 始终优先。宿主可以按原有方式注册业务数据源：

```java
@Bean("biz_db")
DataSource businessDataSource() {
    // 由宿主创建并管理连接池。
}
```

独立开发或测试时，可以开启引擎连接池创建：

```yaml
guo:
  workflow:
    datasources:
      auto-create: true
      definitions:
        biz_db:
          url: ${BIZ_DB_URL}
          username: ${BIZ_DB_USERNAME}
          password: ${BIZ_DB_PASSWORD}
          driver-class-name: com.mysql.cj.jdbc.Driver
          maximum-pool-size: 10
          minimum-idle: 2
          connection-timeout-ms: 30000
```

`auto-create` 默认关闭，`application-local.example.yml` 中为开发环境开启。
引擎只会在宿主不存在同名 Bean 时创建连接池，并在应用关闭时释放自己创建的
连接池。XML 中只保存 `dataSource="biz_db"` 这类逻辑名称，不保存连接凭据。

框架会拒绝 Flowable 主数据源 Bean `dataSource` 以及 `flowable*` 数据源，
业务 SQL 不存在回退到主库的行为。

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
Maven 构建也会把该文件排除在应用制品之外；执行 `mvn spring-boot:run` 时仍会从
`workflow-boot/src/main/resources/application-local.yml` 直接读取本地配置。
部署制品时请通过环境变量，或在运行目录的 `config/application-local.yml` 提供配置，
不要把真实连接信息重新放回源码资源目录。

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

## 流程运维镜像

Flowable 生命周期事件通过全局监听器采集，并在主事务提交后使用独立事务写入：

| 表 | 用途 |
| --- | --- |
| `t_process_instance_mirror` | 流程实例当前状态、当前节点和处理人摘要 |
| `t_process_task_mirror` | 一行一个任务，支持并行、会签和多处理人 |
| `t_process_activity_log` | 流程、节点和任务事件轨迹 |
| `t_process_business_link` | 流程与多个业务单据的关联 |
| `t_process_definition_catalog` | 流程定义版本、设计源和语义快照目录 |

镜像失败只记录日志，不向 Flowable 主事务传播异常。宿主注册 `UserResolver`
后可将 userId 转换为姓名，未注册或解析失败时使用 userId。

Action 配置通过 `process_definition_id + activity_id + trigger_scope + event`
定位，`trigger_scope` 支持 `TASK/NODE/PROCESS`。新配置优先绑定具体流程定义
版本，历史按 `process_definition_key` 配置仍可作为兼容兜底。

外部报表可直接查询只读视图：

```text
v_process_instance
v_process_task
v_process_activity
v_process_business
v_process_definition
```

常用页面查询可以使用 `WorkflowQueryService`；复杂报表仍由外部系统直接编写
只读 SQL，不要求通过通用报表 DSL。

## 审批流程编译

`guo:approval` 是设计态扩展语法。`ApprovalProcessPublisher` 发布流程时会完成：

1. 将 guo 审批语义编译为标准 Flowable BPMN 并部署；
2. 在 `t_process_definition_catalog` 保存原始设计 XML 和 `semantic_json` 语义快照；
3. 将通知等环节行为展开为绑定当前流程定义版本的 action 配置。

部署到 Flowable 的 BPMN 不依赖 guo 标签运行。当前静态编译支持单人审批、并行/顺序
会签、或签，以及 `ALL`、`ANY`、`PERCENTAGE` 完成策略。`VETO`、动态加签和减签
需要运行时投票能力，暂不静态编译。

```xml
<userTask id="finance" name="财务审批">
  <extensionElements>
    <guo:approval mode="COUNTERSIGN"
                  completionPolicy="ALL"
                  sequential="false"
                  approvers="${financeApprovers}">
      <!-- 只允许退回到这里显式列出的节点；不配置即禁止退回。 -->
      <guo:return to="departmentApproval" />
      <guo:notification trigger="assignment"
                        channel="dingtalk"
                        to="#{currentApprover}">
        <guo:title>待办提醒</guo:title>
        <guo:content>业务 #{businessKey} 待审批</guo:content>
      </guo:notification>
    </guo:approval>
  </extensionElements>
</userTask>
```

通知必须显式配置；未配置通知时不会发送消息。会签节点的
`trigger="node-complete"` 在该节点整体完成后触发，普通节点会自动降级为任务完成
事件。节点级 action 由 `ApprovalNodeListener` 在事务提交后分发，并沿用统一的同步/
异步 action 执行链路。

## 流程定义管理

`ApprovalProcessManagementService` 提供设计态流程的校验预览、发布和版本查询：

```java
ApprovalProcessPreview preview = managementService.preview(sourceXml);

PublishedApprovalProcess published = managementService.publish(
        sourceXml,
        tenantId);

List<ProcessDefinitionCatalog> versions = managementService.listVersions(
        tenantId,
        preview.processKey());
```

`preview` 只执行安全解析、guo 语义校验和标准 BPMN 编译，不会创建部署或修改数据库；
返回内容包含生成 BPMN、语义快照、审批节点和待生成 action，可用于发布前确认。
`publish` 才执行部署，并将设计源、标准化 BPMN 语义和 action 绑定到具体流程定义版本。
相同流程 Key 的每次正式发布都会形成新版本，不影响运行中的旧实例。

## 退回与取消

`WorkflowOperationService` 提供经过约束的退回和取消入口。退回目标来自当前节点的
`guo:return` 白名单，调用方不能传入任意历史节点：

```java
operationService.returnTask(new ReturnTaskCommand(
        taskId,
        "departmentApproval",
        "资料不完整"));
```

退回会让 Flowable 取消当前节点的全部活动任务并迁移到目标节点。会签节点按整体退回，
再次进入会签时重新发起一轮审批。退回或取消时，当前节点配置为 `event="delete"` 的
同步 action 会在事务内执行；执行失败会阻止状态迁移。异步 action 则进入现有可靠任务
链路。

补偿 action 可读取两个上下文属性：

```text
#{$attrs.operation}              RETURN 或 CANCEL
#{$attrs.returnTargetActivityId} 退回目标，取消时为空
```

因此逆向 SQL、脚本或业务 Handler 应放在发起退回的当前节点，而不是被退回的目标节点。
`cancelProcess` 调用 Flowable 原生删除实例并保留历史记录，实例最终状态为
`CANCELLED`。技术终止 `TERMINATED` 不由该业务取消接口触发。

## 任务办理

`WorkflowTaskService` 为宿主提供稳定的待办和任务办理接口，避免业务代码直接依赖
Flowable Task API：

```java
List<TaskView> todo = taskService.findTodoTasks(tenantId, operatorId, 50);

taskService.claimTask(new ClaimTaskCommand(taskId, operatorId));
taskService.transferTask(new TransferTaskCommand(taskId, operatorId, targetUserId));
taskService.completeTask(new CompleteTaskCommand(
        taskId,
        operatorId,
        "同意",
        Map.of("approved", true)));
```

领取、退回领取、转办和完成都会校验当前处理人。完成操作会先保存审批意见，再携带
业务变量完成任务，从而仍由现有 TaskListener 和 action 链路处理同步校验、同步业务
动作及异步指令。当前操作人通过 `#{$attrs.operatorId}` 提供给 action。

待办查询覆盖“直接候选人”“候选组”和“已分配任务”。宿主实现可选的
`WorkflowGroupResolver` 将当前用户展开为组织组：

```java
@Component
public class OrganizationGroupResolver implements WorkflowGroupResolver {

    @Override
    public List<String> groupIds(String operatorId) {
        // 返回 operatorId 在宿主组织中的组 ID。
        return organizationService.groupIdsOf(operatorId);
    }
}
```

未注册解析器时自动降级为直接候选人和已分配任务。转入新处理人会触发标准
`assignment` 事件，并可正常展开 assignment 通知。

发起流程可使用带身份和租户的完整命令：

```java
workflowEngineService.startProcess(new StartProcessCommand(
        processKey,
        operatorId,
        tenantId,
        Map.of("orderNo", businessKey, "amount", amount)));
```

发起后会自动准备 `businessKey`、`initiator`、`startUserId`、`tenantId` 等核心变量，
并将 Flowable 历史实例的发起人设置为当前操作人。原有
`startProcess(processKey, variables)` 保留兼容，但不携带租户和操作人。

## 消息发送

消息动作通过统一的 `MessageSender` SPI 路由到具体渠道。宿主只需注册一个
Spring Bean，并让 `channel()` 返回 XML 中使用的渠道名称：

```java
@Component
public class InternalMailMessageSender implements MessageSender {

    @Override
    public String channel() {
        return "internal-mail";
    }

    @Override
    public void send(MessageRequest message) {
        // 调用宿主内部消息服务。
    }
}
```

一个动作允许配置多个 `<message>`，各消息会并发发送：

```xml
<action event="complete" type="job" doType="message" sync="false">
  <message channel="dingtalk"
           to="#{salesUserId},#{$attrs.financeManagerId}"
           format="markdown">
    <title>订单 #{businessKey} 审批通知</title>
    <content>客户 #{customerName} 的订单已通过，金额 #{orderAmount}。</content>
  </message>
</action>
```

`channel`、`to` 和 `content` 必填，`title` 可空，`format` 默认为 `text`。
`to`、`title`、`content` 与 SQL 动作使用同一套占位符规则：普通占位符优先读取
流程变量，再读取 `businessKey` 等核心字段；`#{$attrs.name}` 读取动作属性。

消息并发上限通过 `guo.workflow.message.max-concurrency` 配置，默认值为 `8`。
找不到渠道、接收人为空或渠道发送失败都会抛出 `FlowableActionException`。
异步重试可能重复调用外部渠道，渠道实现应使用消息 ID 或业务键保证幂等。

引擎内置 `dingtalk` 和 `wecom` 群机器人 Webhook 渠道，默认关闭。启用示例：

```yaml
guo:
  workflow:
    message:
      channels:
        dingtalk:
          enabled: true
          webhook: ${DINGTALK_WEBHOOK}
          secret: ${DINGTALK_SECRET}
          recipient-type: user-id  # 或 mobile
        wecom:
          enabled: true
          webhook: ${WECOM_WEBHOOK}
          recipient-type: user-id  # 或 mobile
```

内置渠道面向群机器人：`to` 用于在群内提及用户，不负责企业应用向个人直接发送消息。
需要按应用身份直发时，实现宿主自己的 `MessageSender`。HTTP 连接和请求超时分别通过
`guo.workflow.message.http.connect-timeout-seconds` 和
`guo.workflow.message.http.request-timeout-seconds` 配置。

## 文件导出

文件导出统一委托给专门的导出模块。引擎只负责校验和传递任务定义标识，不负责
格式、取数或模板判断。宿主提供一个 `FileExportGateway` 实现：

```java
@Component
public class ExportModuleGateway implements FileExportGateway {

    @Override
    public String export(FileExportRequest request) {
        // 将 request 交给专门导出模块。
        return "export_ticket_123";
    }
}
```

XML 配置：

```xml
<action event="complete" type="job" doType="export" sync="false">
  <target fileType="csv" expTaskId="task_order_export_csv" />
</action>
```

`fileType` 和 `expTaskId` 必填，action 下只能配置一个 `<target>`。`expTaskId` 是导出
模块中的导出任务定义 ID，类似 BPMN 流程定义 ID；`fileType` 原样传入，不在这里
做格式判别。`FileExportRequest` 还包含完整的 `ActionContext` 和 target 属性，
导出模块内部可以自行决定取数、模板和文件生成方式。找不到导出模块或导出失败时，
统一抛出 `FlowableActionException`，异步任务按既有机制重试。

导出模块返回凭据后，引擎将凭据写入：

```text
exportTicket_<activityId>_<actionId>
```

例如：

```text
exportTicket_managerApproval_orderCsvExport=export_ticket_123
```

为保证变量名在发布和重试后保持稳定，导出 action 应显式配置稳定的 `id`。

动作产生的新变量会序列化到 `t_async_task.result_json`。Flowable 进程实例仍然
活动时，消费者还会通过 `RuntimeService` 将变量写回流程；进程已经结束时，凭据
仍可从异步任务结果或导出模块按稳定幂等键恢复。

`FileExportRequest` 必填的 `uniqueBizKey` 由引擎确定性生成，XML 不需要增加
额外属性。规则为 `WF_EXPORT_V1_` 加 SHA-256 摘要，摘要输入包含租户、流程定义、
业务主键、流程实例、Flowable 任务实例、activityId、actionId、事件、`expTaskId`
和 `fileType`。同一任务实例重试时 key 不变；退回同一环节后 Flowable 会生成新的
任务实例，因此会生成新的 key。

导出模块应以 `uniqueBizKey` 建立唯一约束；已经存在时返回原凭据，避免消费者在
执行成功、结果落库前宕机后重复创建导出任务。引擎同时把 key 写入
`exportUniqueBizKey_<activityId>_<actionId>`，并随凭据保存到
`t_async_task.result_json`。该 key 固定为 77 个字符，导出模块字段长度不得小于
`77`。

## 业务动作

固定业务操作通过 `doType="business"` 接入。引擎不包含具体业务领域代码，由宿主
注册 `BusinessOperationHandler`：

```xml
<action event="complete" type="job" doType="business" sync="false">
  <business actionKey="business.operationKey" version="1">
    <param name="businessId" value="#{businessKey}" />
    <param name="amount" value="#{approvedAmount}" type="decimal" />
  </business>
</action>
```

`param` 以白名单方式从流程上下文取值。完整 `#{expression}` 保留原始类型，
混合文本按字符串处理；可选 `type` 支持 `string/int/long/decimal/boolean`。

宿主实现 `BusinessOperationHandler`，按 `actionKey + version` 注册到 Spring
容器。`LocalBusinessOperationGateway` 负责当前内嵌模式调用；未来可替换为远程
Gateway，而不修改 BPMN 和业务参数契约。

每次调用生成稳定的 `uniqueOperationKey`。成功结果的业务 ID、类型、标题和 URL
会写入流程变量及 `t_process_business_link`，并随动作结果进入
`t_async_task.result_json`。业务动作失败会进入既有重试/死信链路。

## 当前执行器

- `MessageAction`：按 `<message channel>` 路由到可插拔 `MessageSender`，支持多渠道并发。
- `ExportAction`：将 `<target>` 中的导出任务定义提交给统一 `FileExportGateway`。
- `BusinessOperationAction`：按 `actionKey + version` 调用宿主业务动作 Handler。
- `ScriptJobAction`、`ScriptValidateAction`：执行 Groovy 或 JavaScript 脚本。
- `SqlJobAction`、`SqlValidateAction`：执行业务库 SQL 任务和只读校验。
- `StoredProcJobAction`：按 `<proc>` 参数顺序调用 MySQL/Oracle 存储过程，
  支持 ResultSet、标量 OUT 和 CURSOR OUT。

