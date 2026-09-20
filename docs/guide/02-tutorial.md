# 02 · 初学者教程

本教程描述的是 **v0.2 单核 harness** 完成后的样子，不是已经封版的 v0.1。v0.1 只有直接回答，没有工具循环。

## 1. 完成后的系统

完成 **v0.2** 后，用户可以在一个烟火节点上打开内嵌网页，连续与烟火对话。Kernel 能调用真实云模型、执行少量受控工具、保存消息与记忆、把长工作放到后台，并在浏览器断线或进程重启后恢复已提交状态。

v0.2 不是多节点，也不是世界树。那是 v0.3。

## 2. 开始前需要掌握的最少知识

不要求先精通 Spring，但应能解释：

- Java record、enum、class、interface、异常和泛型；
- Maven module、dependency 和 test scope；
- Spring Boot 如何创建 Bean、Controller 和配置；
- HTTP 请求、JSON、SSE 的基本含义；
- SQL 表、主键、外键、事务和索引；
- JUnit 中 Arrange、Act、Assert 的结构。

不熟悉某项时，先做一个十几行的小练习，不要在正式代码里边猜边搭框架。

## 3. 工程应长什么样

```text
wn-server/
├─ pom.xml
├─ api/
│  ├─ pom.xml
│  └─ src/main/java/com/wannian/server/api/
├─ kernel/
│  ├─ pom.xml
│  └─ src/main/java/com/wannian/server/kernel/
└─ app/
   ├─ pom.xml
   ├─ src/main/java/com/wannian/server/app/
   ├─ src/main/resources/db/migration/
   └─ src/main/resources/static/
```

依赖方向只有：

```text
app → kernel → api
```

发现 `api` 依赖 Spring、`kernel` 依赖 Controller、或 `kernel` 直接创建模型 SDK 客户端时，先停止并修正依赖方向。

## 4. 阶段 A：让空工程启动

### 目标

- 三个 Maven module 能编译；
- `app` 可以启动；
- `/internal/live` 返回固定结构；
- 依赖规则有自动测试。

### 创建文件

```text
wn-server/pom.xml
wn-server/api/pom.xml
wn-server/kernel/pom.xml
wn-server/app/pom.xml
WannianApplication.java
LiveController.java
```

### 先不要做

- 不接模型；
- 不建数据库；
- 不创建所有未来包；
- 不加入 Guardian 或 Node；
- 不使用全局可变单例保存对话。

### 完成检查

```text
[ ] mvn package 成功
[ ] api 不依赖 kernel/app
[ ] kernel 不依赖 app/Spring MVC
[ ] app 启动后 live 返回 200
```

## 5. 阶段 B：建立持久化底座

### 目标

用 SQLite 保存 Conversation、Message、Turn 和 OutboxEvent。先证明数据能安全写入和重启读取，再写 Agent Loop。

### 实施顺序

1. 配置独立数据目录，例如 `${WANNIAN_DATA_DIR}/wannian.db`。
2. 启用 WAL、foreign keys 和 busy timeout。
3. 编写 V001 migration。
4. 实现 Repository Adapter，并由领域化 `TurnCommitter` 统一管理最终提交。
5. 写 `TurnCommitter` Interface 测试：Turn、Message 和 OutboxEvent 要么全部提交，要么全部不提交。
6. 重启应用，验证数据仍存在。

### 初学者常见错误

- 把数据库文件放到 `target/`；
- 启动时执行 `CREATE TABLE IF NOT EXISTS` 代替 migration；
- 在多个 Repository 中分别提交事务；
- 使用数据库自增 ID 后又在写入前需要 ID；
- 捕获 SQL 异常后返回空结果。

建议在应用层生成 UUID/ULID，让聚合在写入前已有稳定身份。

## 6. 阶段 C：完成没有模型的 Turn

### 目标

收到文本后创建 Turn，使用 FakeModel 返回固定回答，并原子写入用户消息、助手消息和 outbox。

### 请求路径

```text
TurnController
→ SubmitTurnUseCase
→ TurnEngine
→ TurnCommitter
→ SQLite Repository / Outbox Adapter
```

Controller 只负责：

- 解析请求；
- 调用 use case；
- 把结果映射为 HTTP；
- 不决定记忆、不调用模型、不改变 Turn 状态。

### 完成检查

```text
[ ] 相同 clientRequestId 重复请求只产生一个 Turn
[ ] 用户消息和助手消息都带 conversationId
[ ] Turn 最终为 COMPLETED
[ ] outbox 有一条可交付事件
[ ] 事务失败时没有半条回答
```

## 7. 阶段 D：接入真实云模型

### Seam

Kernel 只依赖 `ModelPort`：

```java
public interface ModelPort {
    ModelDecision decide(ModelRequest request);
}
```

具体厂商 SDK 放在 app 的 Adapter 内。Kernel 不认识 endpoint、HTTP client 或厂商异常类。

### 必须处理

- 请求超时；
- HTTP 错误；
- 限流；
- 响应格式错误；
- 空输出；
- 用户取消；
- 调用预算。

API key 只从外部配置读取，不写入数据库、日志、异常正文或测试快照。

## 8. 阶段 E：实现最小 Agent Loop

这是第一个 `OWNER: USER` 区域。按照 30 号工作簿实现：

```text
构造上下文
→ 请求模型
→ 若 FinalAnswer：结束
→ 若 ToolCall：校验并执行
→ 保存 observation
→ 再次请求模型
→ 达到预算则返回受控失败
```

框架代码可以提前提供循环所需的类型、预算器、工具目录和测试 fake；核心决策代码由用户填写。

## 9. 阶段 F：加入第一个工具

首批工具选择：

- `current_time`：读取指定时区时间；
- `calculate`：只处理受限数学表达式；
- 可选 `http_read`：仅允许白名单、GET、大小与超时受限。

工具调用必须交给深 Module `ToolRuntime`。它在内部完成：

```text
工具存在
→ 参数 schema 合法
→ 权限允许
→ 预算允许
→ operationId 已分配
→ 执行
→ 结果大小限制
→ 保存 ToolOperation
```

Agent Loop 只认识 `ToolRuntime.execute(...)` 及其封闭结果，不逐个调用 Validator、Policy、OperationStore 和具体 Adapter。

不要在 0.1 加入 Shell、任意文件写入或桌面控制。

## 10. 阶段 G：SSE 与可靠交付

业务事务只把事件写入 outbox。SSE publisher 读取已经提交的事件并发送：

```text
数据库提交成功
→ OutboxPublisher 读取未投递事件
→ SSE 发送 eventId
→ 客户端保存 lastEventId
→ 断线后从游标继续
```

SSE 失败不应把 COMPLETED Turn 改回 RUNNING，也不应再次调用模型。

## 11. 阶段 H：后台任务

当工作预计超过前台预算、需要重试或多个步骤时：

```text
Turn 事务内创建 BackgroundTask
→ 同事务提交“任务已接受”回答
→ Turn 完成
→ TaskRuntime 稍后创建并调度 SubAgentRun
```

`TaskRuntime` 隐藏 lease、attempt、retry、cancel 和合法状态转换；其内部通过 `TaskExecutor` seam 调用执行器。v0.2 用本节点 Local Adapter；远程 Worker（wn-agent）更后，不修改任务领域语义。

## 12. 阶段 I：Memory 与 Relationship

这是第二组 `OWNER: USER` 区域。先完成最小正确实现：

- 记忆必须有来源；
- 敏感内容默认不自动长期保存；
- 冲突内容不能静默覆盖；
- Relationship 更新必须有原因和 revision；
- 模型只能提出候选，领域规则决定是否提交。

`MemoryRuntime` 与 `RelationshipRuntime` 只返回 `ApprovedChange`；它们不能自行开启数据库事务。正式变化由 `TurnCommitter` 与 Turn、Message 和 Outbox 一起提交。

不要在首版追求向量数据库、复杂心理模型或自动无上限总结。

## 13. 阶段 J：恢复与资源验收

在以下位置注入进程退出：

1. Turn 刚创建；
2. 工具完成但 observation 尚未提交；
3. 回答提交前；
4. 回答已提交但 SSE 尚未发送；
5. Task Run 执行中。

重启后验证：无双 Owner、无重复副作用、已提交事件可补发、未提交结果不会伪装完成。

最后在 2 核 2 GB 等价限制下记录启动、普通 Turn、工具 Turn 和后台任务的内存峰值。

## 14. 何时算完成

只有 [实施清单](01-checklist.md) 的 **v0.2（K01–K07）** 全部通过，且核心 `OWNER: USER` 实现经过测试和审阅，才能称单核 harness 完成。页面能聊天、一次模型调用成功或 Maven 能打包都不等于完成。v0.1 只封直接回答，不含本教程描述的 Loop。

