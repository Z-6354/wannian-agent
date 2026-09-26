# 0.2.4-C · REVIEW（代码已入仓）

`date`: 2026-09-24  
`status`: 垂直链代码入 `wn-server/`；窄编译通过；无新测文件；无页面/AI 标题。

## 已入仓文件清单

### 施工 / 文档
- `docs/plans/archive/0.2.4/k04-c-streaming-delivery-implementation.md`
- `docs/plans/archive/0.2.4/k04-c-draft/REVIEW.md`（本文件）
- `docs/plans/README.md` / `docs/plans/roadmap.md`（状态行）

### Kernel
- `wn-server/kernel/.../error/ErrorCodes.java`（+`STOP_NOT_ALLOWED`）
- `wn-server/kernel/.../model/ModelPort.java`
- `wn-server/kernel/.../model/ModelStreamObserver.java`
- `wn-server/kernel/.../model/ModelCallContext.java`
- `wn-server/kernel/.../turn/TurnTerminalWriter.java`
- `wn-server/kernel/.../turn/TurnRunListener.java`
- `wn-server/kernel/.../turn/TurnEngine.java`
- `wn-server/kernel/.../agent/AgentActivityListener.java`
- `wn-server/kernel/.../agent/DefaultAgentLoop.java`

### App · 持久化 / 模型 / 流
- `wn-server/app/.../db/migration/V015__turn_scheduler_index.sql`
- `wn-server/app/.../persistence/SqliteTurnTerminalWriter.java`
- `wn-server/app/.../persistence/SqliteOutboxQuery.java`
- `wn-server/app/.../persistence/SqliteTurnQueue.java`
- `wn-server/app/.../persistence/SqliteTurnCommitter.java`（+`MessageCommitted`）
- `wn-server/app/.../model/OpenAiCompatibleModelAdapter.java`（`stream:true` SSE）
- `wn-server/app/.../model/TimeoutModelPort.java`
- `wn-server/app/.../model/ResolvingModelPort.java`
- `wn-server/app/.../model/FakeModelAdapter.java`
- `wn-server/app/.../stream/RunEvent.java`
- `wn-server/app/.../stream/RunEventBus.java`
- `wn-server/app/.../stream/TurnRunContext.java`
- `wn-server/app/.../stream/DurableTurnScheduler.java`
- `wn-server/app/.../stream/StreamDeliveryConfig.java`
- `wn-server/app/.../manage/ManageAuthFilter.java`（覆盖 `/api/conversations/**`）
- `wn-server/app/.../chat/TurnEngineConfig.java`（AgentLoop/TurnEngine 迁出）

### App · HTTP / 前端包装
- `wn-server/app/.../http/AsyncTurnController.java`
- `wn-server/app/.../http/TurnControlController.java`
- `wn-server/app/.../http/ConversationSseController.java`
- `wn-server/app/.../http/HttpMapping.java`（错误码映射）
- `wn-server/app/.../http/TurnController.java`（旧同步路径保留）
- `META-INF/resources/chat/api.js`（async / SSE / stop / status）

## 编译

`mvn -pl app -am compile -s D:\0HAN\HANAGENT\.mvn\settings.xml` → **exit 0**

## 审阅修复（2026-09-24 · 针对首次不通过）

| 项 | 处理 |
|----|------|
| P0 SSE 不投递新 Outbox | `ConversationSseController` 连接存续期 400ms 轮询 `listAfter`；心跳 comment；订阅时回放 `RunEventBus.recent` |
| P0 崩溃堵死会话 | `SqliteTurnQueue.listClaimedOrRunning/listCommitting/listExpiredClaims`；`DurableTurnScheduler` 启动/周期 reconcile：孤儿 CLAIMED/RUNNING → FAILED+Outbox；COMMITTING → `TurnEngine` 恢复提交 |
| P1 DiscardPolicy/inFlight | `AbortPolicy`；`executeOne` 外层 finally 必清 inFlight |
| P1 tool.started `{}` | Loop 传原始 arguments；`StreamingActivityListener` 经 `TurnToolCallProjector.safeArgumentsJson` 脱敏 |
| P2 同步 Stop | `TurnController` 同步 execute 前后登记 `ActiveTurnRegistry` |

## 未做（明确）

- 无新 `*Test.java` / 窄测 / 真人
- 未改 `chat/app.js` / `index.html`（D）
- 无 AI 标题 Job（E）

## 审阅修复（独立审阅 P0/P1 · 2026-09-24）

针对独立审阅结论的必修项；P2 顺手一并处理。`mvn -pl app -am compile` → **exit 0**。

### P0-1 SSE 不投递新 Outbox

- **文件**：`ConversationSseController.java`
- **修法**：连接保持期间 `ScheduledExecutorService` 每 400ms 按 `lastSentSequence` 有界 `listAfter`；新行仍用持久 `id=sequence_no` 推送；临时事件不设 SSE id；每 15s comment 心跳；订阅时 `RunEventBus.recent` 回放。
- **逻辑验证**：客户端已连上后，Worker commit/fail/cancel 写入 Outbox → 下一轮 poll 应发出 `message.committed` / `turn.completed|failed|cancelled`，无需重连。

### P0-2 崩溃态堵死同会话

- **文件**：`SqliteTurnQueue.java`（+`listClaimedOrRunning` / `listCommitting` / `listExpiredClaims`）、`DurableTurnScheduler.java`（注入 `TurnTerminalWriter`；`ApplicationRunner` 启动先 `reconcileStartup` 再扫 RECEIVED；周期 30s 扫过期 lease）
- **修法**：启动时全部 CLAIMED/RUNNING（无内存 owner）→ `Turn.fail(CLAIM_EXPIRED)` + `terminalWriter.saveFailed`（含 Outbox）；COMMITTING → `TurnEngine.execute` 恢复提交（不重跑工具）。周期仅处理 lease 过期项 + 无活动的 COMMITTING 再试恢复。
- **逻辑验证**：库中残留 CLAIMED/RUNNING 堵塞 `conversationBusy` 时，重启后应变 FAILED 并解除同会话 FIFO；COMMITTING 有冻结计划时应完成提交。

### P1 DiscardPolicy / inFlight

- **核对**：已是 `AbortPolicy`；`executeOne` 外层 `finally` 清 `inFlight`；提交 Worker 失败路径亦 `remove`。无 DiscardPolicy，无需再改。

### P1 tool.started 参数摘要恒为 `{}`

- **文件**：`DefaultAgentLoop.java`（传原始 `argumentsJson`）、`DurableTurnScheduler.StreamingActivityListener`（注入 `TurnToolCallProjector.safeArgumentsJson` 再发布）、`AgentActivityListener` 注释对齐
- **逻辑验证**：`tool.started` 的 `argumentsSummary` 应为投影仪白名单/hidden 结果，不再恒为 `{}`；密钥类参数不得原文出现。

### P2（顺手）

- **同步 Stop**：`TurnController` 在 `execute` 前后登记/注销 `ActiveTurnRegistry`（用 budget 的 `CancelToken`）。
- **SSE**：同上，已含 recent 回放 + 心跳。

### 禁止项遵守

- 未写新测试文件；未改 `app.js` / D 页面；未改 V001–V014。
