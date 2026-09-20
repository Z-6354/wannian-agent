# 01 · 内核合同

`date`: 2026-09-18  
`status`: **amended** — 2026-09-20 版本重置。本文后半仍是内核形状（Turn、提交、工具、探针）。**交付档次以 [产品概览 §3](../product/01-overview.md) 为准：**

- **v0.1** 已停在直接回答，不含 Agent Loop
- **v0.2** 做成单核 harness 与单核节点（原「v0.1 必须含 Loop」整包改记到这里）
- **v0.3** 世界树与多核节点
- Guardian、wn-agent、代际换代在更后，不是产品 v0.2

进度见 [实施清单](../guide/01-checklist.md)。不要按文内旧句「v0.1 必须有工具循环」把未做的 harness 算成 v0.1 欠账。

## 1. 审查结论

v0.2 的单核 harness 不能只有一次 LLM 调用。没有工具决策、观察回送、预算和终止条件，它只是聊天。聊天直接回答已经在 v0.1 交付。

版本恢复不能放进 Kernel。Kernel 可以暴露版本号和探针，但不得拥有稳定版本指针、回退策略或 Guardian 状态。

Turn 提交必须先于可靠交付。回答正文、状态变化和 outbox 事件在同一事务边界内提交。SSE 断开不能导致重新执行模型或有副作用工具。此条在 v0.2 接 Outbox 时生效；v0.1 还没有这条产品路径。

工具属于 harness，节点角色属于部署。v0.2 在**同一个节点**上做受控工具。v0.3 才做多节点与世界树。跨设备 Worker、Guardian、代际换代更后，且 Guardian 必须与普通 Agent 分开。

生产路径在对应版本必须是真模型、真持久化，不能靠 stub 宣称完成。v0.2 Loop 验收不用 Fake。Companion 与 Memory 可以算法简单，但不能只有空接口——这是 v0.2 的完成线，不是 v0.1。

### 会话内执行中新输入（2026-09-20 增补）

同会话在已有非终态 Turn / 活跃 Run 时又收到新用户输入：

- **不拒收**；新输入落库并进入会话队列（通常为 `RECEIVED`）。
- 调度模式固定为 **followup（FIFO）**：当前 flight 整轮终态后再执行下一条；**同会话不双 RUNNING**。
- **不做默认 Steer**（不把新话注入正在跑的 Loop）；新话不自动 cancel 当前 Run。
- 改口仅经 **显式 cancel**（COMMITTING 前；与 `freezeCommit` CAS 决胜）。
- **DecisionPort / Jev 不参与**该调度裁决（Jev 仍可用于分享/门控等）。

正文与通道表现见 [in-flight-user-message.md](../research/in-flight-user-message.md)。
## 3. 清单改记到哪一档

下面原「v0.1 必须完成」清单不删，只改归属。已在 v0.1 落地的不再重做。

| 原条目 | 现行 |
|--------|------|
| 1–4、9 的内嵌页、10 的会话库 | **v0.1 已有**（直接回答，不是 Loop） |
| 5–8、9 的 SSE/outbox、11 探针 | **v0.2** 单核 harness |
| 多节点、世界树 | **v0.3** |
| wn-agent、Guardian、artifact、代际升级、EVOLVER | **其后** |

### 3.1 原必须清单（归属见上表）

1. 单用户身份与最小鉴权。
2. Conversation、Message、Turn 的持久化。
3. 普通 Turn 的唯一所有权、状态机和崩溃后终态判定。
4. 一个真实云模型 Adapter，以及超时、限流和错误映射。
5. 最小 Agent Loop：模型决策、工具调用、观察回送、最终回答。
6. 服务端工具注册表、权限、参数校验、预算、超时和 `operationId`。
7. 最小长期记忆与关系状态，具有真实读写和来源记录。
8. 最小 BackgroundTask 与 SubAgentRun，使长任务不占住聊天 Turn。
9. HTTP API、SSE/outbox 游标和内嵌 Web 对话入口。
10. SQLite 权威存储、WAL、启动迁移、备份导出和重启恢复。
11. 外部可判定的存活、就绪和版本探针。探针不等于 Guardian。

### 3.2 不再叫 v0.2 的延期项

```text
v0.3:
  世界树
  多核节点（设备 / 节点 / 角色）

其后:
  wn-agent / Worker
  Wannian Guardian
  artifact 与 Generation
  代际升级、EVOLVER
  数据库高可用与多主
```

## 4. 最小模块边界

服务端继续采用三层交付单元。页面与浏览器端 adapter 属于 `app`；共享视觉单独做成同级样式包：

```text
wn-server/
├─ api
│  └─ 外部 DTO、错误码、事件与版本化协议
├─ kernel
│  ├─ conversation
│  ├─ turn
│  ├─ agentloop
│  ├─ task
│  ├─ memory
│  ├─ relationship
│  ├─ model
│  └─ tool
└─ app
   ├─ http / sse / auth
   ├─ persistence-sqlite
   ├─ tool-adapters
   ├─ platform / probes
   └─ /manage/、/chat/ 页面与浏览器端 API adapter
../wannian-ui/
   └─ /ui/ tokens、themes、components、layouts（独立样式包）
```

依赖规则：

```text
wn-server-app → kernel → api
wn-server-app → wannian-ui
```

- `kernel` 不依赖 Spring MVC、SQLite 驱动或具体模型 SDK。
- `api` 不包含数据库实体和框架注解。
- `app` 只做适配、装配与进程宿主，不在 Controller 编写 Agent 决策。页面 HTML、页面 JavaScript 与 API adapter 也在 `app` 内。
- `wannian-ui` 只拥有共享视觉资源。`app` 直接依赖它，不经过另一个 Web 模块。
- 页面与样式都使用 `META-INF/resources/`，因此 `/manage/`、`/chat/`、`/ui/` URL 保持不变；不引入 Node/Vite 或独立 Web 进程。
- v0.1 不创建 `node`、`generation`、`guardian` 包或空占位工程。

## 5. 最小 Agent Loop

一次普通 Turn 使用以下确定流程：

```text
1. 接收并持久化 TurnRequest
2. 认领 Turn，固定 executionId 与输入 stateRevision
3. 读取 Conversation、Memory、Relationship 和工具目录
4. 调用云模型
5. 若模型给出工具调用：
   a. 校验工具、参数、权限和预算
   b. 分配稳定 operationId
   c. 执行工具
   d. 持久化 ToolCall 与 ToolObservation
   e. 把观察结果送回模型
6. 若工作必须后台化：原子创建 BackgroundTask 并回复已接受
7. 否则产生最终回答
8. 原子提交 Message、允许的状态变化和 OutboxEvent
9. Turn 进入终态，SSE 按事件游标投递
```

首版硬限制：

- 单 Turn 最多 3 次模型决策；
- 前台软预算 15 秒，硬上限 30 秒；
- 单 Turn 同时只有一个有效执行者；
- 工具默认禁止写文件、执行 Shell 和访问生产凭据；
- 任何副作用工具必须支持 `operationId` 幂等或明确禁止自动重试；
- 模型输出不是状态提交权限，所有修改必须经过领域命令校验。

## 6. Turn、Task 与 Run

### 6.1 Turn

```text
RECEIVED → CLAIMED → RUNNING → COMMITTING → COMPLETED
                           └──────────────→ FAILED
任意非终态 ─────────────────────────────→ CANCELLED
```

`STREAMING` 不是 Turn 状态；它属于 outbox 交付游标。

### 6.2 BackgroundTask

```text
CREATED → READY → RUNNING → SUCCEEDED
                   ├─────→ WAITING
                   ├─────→ FAILED
                   └─────→ CANCELLED
```

### 6.3 SubAgentRun

```text
CREATED → LEASED → RUNNING → SUCCEEDED
                         ├→ FAILED
                         ├→ CANCELLED
                         └→ LOST
```

v0.1 的 Run 只在同一个 wn-server 进程或其受控本机执行器中运行，不涉及远程 Worker Node。Task 重试必须创建新 Run，不复活旧 Run。

SubAgentRun 只能提交 TaskResult、ArtifactReference 和 Evidence；不能直接写长期 Memory、Relationship，不能直接冒充烟火向用户发送最终表达。

## 7. 首版持久对象

最小表/聚合：

```text
conversation
message
turn
turn_step
tool_operation
background_task
sub_agent_run
memory_record
relationship_state
outbox_event
schema_migration
```

共同字段按需包括：

```text
id
revision
status
createdAt
updatedAt
operationId
errorCode
```

v0.1 不创建 `generation`、`kernel_replica`、`serving_lease` 或 Guardian ref 表。运行版本只作为只读 build metadata 暴露。

## 8. SQLite 决策与边界

v0.1 采用 SQLite，理由是单用户、单服务节点和 2 核 2 GB 环境下更简单，并且不把数据库运维变成内核首版前置条件。

必须满足：

- WAL 模式；
- 明确事务边界；
- 单写者冲突重试有上限；
- migration 前自动备份；
- 数据文件不放在 artifact 或可替换运行目录内；
- 提供一致性备份命令，不直接复制正在写入的主文件；
- 美国节点恢复与状态高可用不在 v0.1 承诺范围内。

如果实测写入竞争、备份窗口或未来接班需求超出 SQLite 边界，再通过持久化 Adapter 迁移；不得为了未来假设在 v0.1 提前搭建数据库集群。

## 9. 外部生命周期协议预留

v0.1 只提供 Guardian 将来需要的稳定、小型接口：

```text
GET /internal/live
GET /internal/ready
GET /internal/version
POST /internal/drain
```

语义：

- `live`：进程事件循环仍能响应；
- `ready`：数据库可用、migration 完成、核心依赖已装配；
- `version`：返回 build ID、source revision、protocol version；
- `drain`：停止接收新 Turn，并等待当前 Turn 到达终态。

这些接口不包含升级、稳定版本登记或回退命令。v0.1 Kernel 不知道 `STABLE/PREVIOUS/ACTIVE` 指针。

## 10. 禁止的错误修法

- 把 Guardian 写成 Spring Bean 放进 wn-server。
- 为了将来多节点，在 v0.1 实现选主、共识或跨云写入。
- 用 SSE 连接存活表示 Turn 是否成功。
- 先向用户返回“后台任务已创建”，再异步写 Task。
- 工具超时后无条件重复执行有副作用请求。
- 让 SubAgent 直接写正式记忆、关系状态或最终回复。
- 把模型原始自由文本当作内部状态机命令。
- 将 SQLite 数据文件放在构建输出或版本 artifact 目录。
- 以 Git commit 替代实际运行 artifact digest。
- 在 v0.1 建设只有接口、没有真实行为的大量空模块。

## 11. 可控复现与验收断言

### 11.1 普通 Turn

1. 启动 wn-server，创建 Conversation 并连续完成至少三轮真实模型对话。
2. 一个 Turn 触发只读工具，观察结果返回模型后形成最终回答。
3. 重复提交同一客户端 request ID，不生成两个 Turn 或重复副作用。
4. SSE 中断后按游标重连，不重新执行 Agent Loop。

### 11.2 后台任务

1. 长任务在同一事务内创建 Task 和“已接受”响应。
2. 原 Turn 完成后可以继续新对话，不等待 Task。
3. Run 失败后按策略新建 Run；旧 Run 的证据保持不变。
4. Task 完成后由 Kernel 生成新的交付事件，SubAgent 不直接面向用户。

### 11.3 崩溃与恢复

1. 在 `RUNNING`、工具完成后、`COMMITTING` 前后分别注入进程终止。
2. 重启后不存在两个有效 Turn Owner。
3. 已提交响应可以重新投递，未提交响应不会伪装完成。
4. 状态未知的副作用操作不会自动盲重试。
5. SQLite 备份可在独立临时目录恢复并通过一致性检查。

### 11.4 资源边界

1. 在国内 2 核 2 GB 等价限制下启动。
2. 空闲、普通 Turn、工具 Turn、后台任务各记录内存峰值。
3. 并发超过配置上限时排队或拒绝，不触发无限线程和无限模型调用。

## 12. 实施顺序

全档顺序以 [路线图](../plans/roadmap.md) 为准；勾选以 [实施清单](../guide/01-checklist.md) 为准。下列为合同**原编号**（历史对照，不要按此表当现行开工单）：

```text
原 K01–K02  → 历史 H1–H2（已在 v0.1）
原 K03 模型/Loop 等 → 现行 v0.2 的 **0.2.1** 起（别名 K01；见路线图）
…
```

依赖：Task 复用 Turn、工具、outbox 和持久化语义；不能先搭独立任务框架再反向拼接。世界树与多核属 **v0.3**，不在 v0.2 提前做。

## 13. 执行授权与交付格式

当前仍是文档审查阶段。未授权：

- 创建产品代码；
- 修改生产或部署配置；
- commit、push、发布；
- 启动 0.2 Guardian 实施。

执行 Agent 每个 K 项应回传：

```text
修改文件
实现的不变量
运行命令与退出码
针对验收断言的测试证据
遗留问题
是否触及任务外文件
```

审阅者必须检查实际差异与测试输出，不能仅按执行者自报“完成”验收。

## 14. 下一决策

在代码实施前只剩一个产品级范围选择：v0.1 的第一个真实服务端工具具体选什么。默认建议采用无副作用、容易稳定验收的“当前时间/计算”与受限 HTTP 读取工具；Shell、任意文件写入、桌面操作和跨设备工具全部推迟。
