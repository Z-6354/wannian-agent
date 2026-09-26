# 0.2.4-C · 异步执行、模型流和事件交付施工单

`status`: **代码已入仓 · 2026-09-24**（普通阶段门：用户授权直接入 `wn-server/`；无新测文件；`mvn -pl app -am compile` 通过）  
`upstream`: [已审范围](./k04-conversation-system-draft.md) · [系统设计](./k04-conversation-system-design.md) §5–9 / §11-C / §12 · [B 施工单](./k04-b-conversation-readwrite-implementation.md) · [工作流](../../version-stage-workflow.md)  
`alias`: K04-C  
`depends`: **0.2.4-B** 会话读写 API 已交付（本阶段不重做列表/历史/生命周期）  
`draft`: `docs/plans/archive/0.2.4/k04-c-draft/`（对照 / REVIEW；生产在 `wn-server/`）

## 0. 目标与边界

### 必须达成

1. **异步 receive**：新路由快速返回 `conversationId` / `turnId` / `status` / `replayed`，不等待模型；复用 `TurnCommitter.receive`。旧同步 `POST .../turns` 保持可用。
2. **Durable 调度**：按库中 `RECEIVED` Turn 同会话 FIFO 调度；进程内有界 Worker；重启可找回待执行项并尊重 claim/lease；同会话最多一个 RUNNING。
3. **模型真流式**：`ModelPort` 增量观察；同一次请求累积完整 `ModelOutcome`；`OpenAiCompatibleModelAdapter` 用 `stream:true` + SSE 解析正文 delta；不透出 reasoning；工具参数分片组装完整合法 JSON 后再执行；Timeout/取消/deadline 对流式生效。
4. **两类事件**：运行中临时（有界内存 + `(executionId, runSeq)`）；已提交 Outbox（同事务；按 `sequence_no` 补发）。失败/取消补 Outbox。
5. **SSE**：按 `conversationId` 订阅；鉴权与聊天 HTTP 一致；持久 cursor 补发 Outbox；临时序号不作 Outbox cursor。
6. **Stop / 撤队 / Turn 状态 HTTP**：Stop 指 turnId；RUNNING 传取消；COMMITTING 不伪称已停；RECEIVED 可撤队→CANCELLED；幂等。
7. **鉴权**：`/api/conversations/**`（含 SSE）与 manage 同一最小单用户策略（回环/token）。

### 明确不做

- D 页面布局（禁止改 `chat/app.js` / `index.html`）
- E AI 标题 Job
- F 窄测 / 真人 / 任何新 `*Test.java`
- Prompt/Skill、MemoryReview 主逻辑、删除会话级联记忆
- 世界树 / 多节点 / BackgroundTask
- 未提交 token 写成正式 Message / 可靠 Outbox
- Fake 伪流式冒充验收（`wannian.model.mode=fake` 仅开发短路，不宣称流式完成）

## 1. 已核实代码事实

1. `TurnController`：`receive` 后同步 `TurnEngine.execute`，整轮返回。
2. `ModelPort` 仅 `decide`；`OpenAiCompatibleModelAdapter` 用 `BodyHandlers.ofString()`；`TimeoutModelPort` 包非流式 Future。
3. `TurnEngine` 同会话公平锁串行 RECEIVED/COMMITTING；`failAttempt` / `cancelAttempt` 只 `TurnRepository.save`，**无 Outbox**。
4. `SqliteTurnCommitter` 成功路径写 `TurnCompleted` Outbox（payload 含 `conversationId`）；无 `MessageCommitted` / `TurnFailed` / `TurnCancelled`。
5. `ManageAuthFilter` 仅 `/api/manage/**`。
6. B 已交付会话 CRUD/历史/搜索；最高 migration **V014**；本阶段新表/索引从 **V015**。
7. `AgentBudget.CancelToken` 可跨线程置位；`ModelCallContext.cancelled` 现为 decide 前快照，流中需 live 探测 + interrupt。

## 2. 不变量

1. Message 写入仍只经 receive / commit；SSE 不触发 Loop/Tool。
2. 调度真源 = SQLite `turn` 行；内存队列只是唤醒提示。
3. 同会话：存在 `CLAIMED|RUNNING|COMMITTING` 时不 claim 下一条 `RECEIVED`。
4. 运行中 delta 只进进程内有界缓存；重启不承诺逐 token 补齐。
5. Outbox `sequence_no` 全局递增；SSE `id` / 客户端 cursor 只用持久序号。
6. COMMITTING 不可 `cancel`（领域已禁）；Stop API 返回明确「提交中无法停止」。
7. 旧同步 POST 行为不变（仍可阻塞至完成）；异步路径不调用同步 execute。

## 3. HTTP 契约

| 方法 | 路径 | 要点 |
|------|------|------|
| POST | `/api/conversations/{id}/turns` | **旧同步**：receive + execute；响应可含 `reply` |
| POST | `/api/conversations/{id}/turns/async` | **新**：仅 receive + 唤醒调度；`{result,conversationId,turnId,status,replayed}`；无 `reply` |
| GET | `/api/conversations/{id}/turns/{turnId}` | 当前 `status` / `errorCode` / `executionId`（若有） |
| POST | `/api/conversations/{id}/turns/{turnId}/stop` | RECEIVED→撤队 CANCELLED；RUNNING→cancel token；COMMITTING→`STOP_NOT_ALLOWED`；终态幂等 |
| GET | `/api/conversations/{id}/events?afterSequence=` | SSE；先补发 Outbox `sequence_no > afterSequence`；再推临时+新 Outbox；心跳注释行 |

错误码新增（集中登记）：

- `STOP_NOT_ALLOWED` — COMMITTING 或状态不允许停止
- `TURN_QUEUE_CANCELLED` — 排队项已撤（可与 `CANCELLED` 并存作 detail；稳定码用 `CANCELLED` 亦可，本单用 `CANCELLED` + 明确 detail，另登记 `STOP_NOT_ALLOWED`）

鉴权：扩展后所有上表路径与 B 会话 API 同策略。

## 4. 事件 schema

### 4.1 运行中（临时，非 Outbox）

| type | 关键字段 |
|------|----------|
| `turn.started` | conversationId, turnId, executionId, runSeq, at |
| `reply.delta` | conversationId, turnId, executionId, runSeq, text |
| `tool.started` | conversationId, turnId, executionId, runSeq, callId, operationId, name, argumentsSummary, at |
| `tool.updated` | 同上 + status, errorCode?, finishedAt, resultSummary? |

去重键：`(executionId, runSeq)`。缓存：每会话最近 N 条（默认 256），全局连接/订阅有界。

### 4.2 已提交（Outbox `event_type`）

| event_type | SSE 对外 type | 说明 |
|------------|---------------|------|
| `MessageCommitted` | `message.committed` | 与助手 Message 同事务；payload: conversationId, turnId, messageId, role, textPreview |
| `TurnCompleted` | `turn.completed` | 保留现有；payload 已有 conversationId |
| `TurnFailed` | `turn.failed` | fail 路径同事务 |
| `TurnCancelled` | `turn.cancelled` | cancel / 撤队同事务 |

## 5. 调度不变量

- Worker 数默认 2（2C2G）；轮询/唤醒找下一条可跑 Turn。
- 可跑 = `status=RECEIVED` 且同会话无 `CLAIMED|RUNNING|COMMITTING`，按 `(created_at, id)` FIFO。
- 执行入口：`TurnEngine.execute(ExecuteTurn)`（与同步路径同一 Loop）。
- 进程内登记 `turnId → CancelToken` + worker 线程，供 Stop。
- 启动时扫描并调度；lease 过期由现有 claim 语义处理，不盲目重跑 COMMITTING 外副作用。

## 6. Migration V015

```text
CREATE INDEX IF NOT EXISTS idx_turn_status_conversation_created
  ON turn (status, conversation_id, created_at, id);
```

不改 V001–V014；Outbox 不强制加列（用 payload `conversationId` + `json_extract` 过滤）。

## 7. 文件 MANIFEST（生产路径）

| 序 | 文件 | 目的 |
|----|------|------|
| C1 | `docs/plans/archive/0.2.4/k04-c-streaming-delivery-implementation.md` | 本施工单 |
| C2 | `kernel/.../error/ErrorCodes.java` | +`STOP_NOT_ALLOWED` |
| C3 | `kernel/.../model/ModelStreamObserver.java` | 增量观察接口 |
| C4 | `kernel/.../model/ModelPort.java` | default 流式 decide |
| C5 | `kernel/.../model/ModelCallContext.java` | live cancel 探测 |
| C6 | `kernel/.../turn/TurnTerminalWriter.java` | 终态+Outbox 同事务端口 |
| C7 | `kernel/.../turn/TurnEngine.java` | cancel/fail 走 TerminalWriter；可选 run hook |
| C8 | `kernel/.../agent/DefaultAgentLoop.java` | 传 CancelToken；工具前后发临时事件钩子 |
| C9 | `app/.../manage/ManageAuthFilter.java` | 覆盖 `/api/conversations` |
| C10 | `app/.../resources/db/migration/V015__turn_scheduler_index.sql` | FIFO 索引 |
| C11 | `app/.../persistence/SqliteTurnTerminalWriter.java` | 终态+Outbox |
| C12 | `app/.../persistence/SqliteTurnCommitter.java` | +MessageCommitted；失败码不变 |
| C13 | `app/.../persistence/SqliteOutboxQuery.java` | 按会话+sequence 读 |
| C14 | `app/.../persistence/SqliteTurnQueue.java` | 查下一条 RECEIVED / 会话忙 |
| C15 | `app/.../model/OpenAiCompatibleModelAdapter.java` | stream SSE |
| C16 | `app/.../model/TimeoutModelPort.java` | 流式超时 |
| C17 | `app/.../model/ResolvingModelPort.java` | 转发 observer |
| C18 | `app/.../model/FakeModelAdapter.java` | 兼容 observer（非验收） |
| C19 | `app/.../stream/RunEventBus.java` | 有界临时事件 |
| C20 | `app/.../stream/TurnRunContext.java` | ThreadLocal 运行上下文 |
| C21 | `app/.../stream/DurableTurnScheduler.java` | 调度+Worker |
| C22 | `app/.../stream/StreamDeliveryConfig.java` | Bean 装配 |
| C23 | `app/.../http/AsyncTurnController.java` | async receive |
| C24 | `app/.../http/TurnControlController.java` | status / stop |
| C25 | `app/.../http/ConversationSseController.java` | SSE |
| C26 | `app/.../http/TurnController.java` | 保持同步；注释标明兼容 |
| C27 | `META-INF/resources/chat/api.js` | async/SSE/stop/status 包装 |
| C28 | `docs/plans/README.md` / `roadmap.md` / `k04-c-draft/REVIEW.md` | 状态 |

**禁止触及**：V001–V014 原文、`chat/app.js`、`index.html`、Prompt/Skill 主逻辑、MemoryReview 决策、测试新文件。

## 8. 与 D/E 交接

- D：用 `api.js` 的 `sendTurnAsync` / `subscribeConversationEvents` / `stopTurn` / `getTurnStatus` 接侧栏与流式 UI；合并规则见设计 §7.3。
- E：排队文案 / Stop UI 细化；首轮 AI 标题 Job 仍未做；本阶段已提供 Stop/撤队 HTTP 与 `RECEIVED` 持久排队语义。

## 9. 验收备忘（留给 F）

真流式（live 模型）、工具参数分片、断线 Outbox 补发、重启不丢 RECEIVED、Stop×COMMITTING、鉴权非回环拒、同步路径回归。
