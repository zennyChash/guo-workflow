# guo-workflow

基于 Flowable 7 的业务动作扩展引擎骨架。目标是在 BPMN 生命周期上挂载 XML
声明的校验、业务 SQL、脚本和对冲动作，并将通知、单据生成等动作解耦为异步指令。

当前阶段只提供工程骨架和契约，不包含 XML 解析器、监听器、执行器或业务表的具体实现。

## 技术基线

- JDK 17+
- Spring Boot 3.3.x
- Flowable 7.0.x
- Groovy 4
- Dom4j
- JDBC（不使用 ORM）
- SLF4J + Logback

本机若默认使用 Java 8，需要先把 `JAVA_HOME` 指向 JDK 17 或更高版本再构建。

## 模块

| 模块 | 职责 |
| --- | --- |
| `workflow-contract` | 无框架依赖的动作契约、上下文、消息和异常 |
| `workflow-core` | Trigger、Dispatcher、Executor 的扩展点 |
| `workflow-boot` | Spring Boot 启动、Web 异常兜底和本地运行配置 |

依赖方向固定为 `workflow-boot -> workflow-core -> workflow-contract`。

## 已提供的契约

- `ActionDefinition`、`ActionInvocation`、`ActionContext`：动作声明与执行上下文。
- `BusinessAction`：所有执行器必须实现的统一接口。
- `ActionDefinitionParser`：节点 XML 的解析边界。
- `ActionDispatcher`：同步动作与异步指令的分发边界。
- `BusinessActionRegistry`：按动作类型路由策略实现。
- `InstructionPublisher`：异步指令的投递边界。
- `InstructionMsg`：通知、单据生成等消费者使用的消息结构。
- `FlowableActionException`：同步校验失败时触发流程回滚的业务异常。

## 本地启动

PowerShell 中先临时切换到本机 JDK 17：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
```

然后执行：

```bash
mvn clean verify
mvn -pl workflow-boot -am spring-boot:run
```

本地 profile 默认使用内存 H2，仅用于启动 Flowable。真实业务数据源尚未接入，
后续必须通过独立连接池配置，禁止复用 Flowable 主数据源。

健康检查：`GET http://localhost:8080/actuator/health`

## 后续实现顺序

1. 固化 XML 契约并实现 `ActionDefinitionParser`，补齐 XML 样例测试。
2. 实现 Map 路由的动作注册表与同步/异步 Dispatcher。
3. 分别实现校验、SQL、存储过程、脚本和指令生产者。
4. 接入 Flowable 生命周期监听器与逆向业务对冲。
5. 增加业务数据源、`t_action_log` 幂等落库和异步消费者。

