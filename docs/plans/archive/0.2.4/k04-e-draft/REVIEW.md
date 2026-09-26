# 0.2.4-E 阶段代码审阅备忘

`status`: **实施已交付 · 主会话审阅已修 P0/P1 并开工窄测** — 2026-09-24  
`scope`: followup / Stop / 撤队 UI + 首轮异步 AI 标题（服务端 Worker + Outbox SSE）  
`note`: 审阅修复与首批测见 [`../k04-f-draft/REVIEW.md`](../k04-f-draft/REVIEW.md)。

## 交付摘要

### 服务端

| 项 | 位置 | 说明 |
|----|------|------|
| 异步标题任务 | `app/title/ConversationAutoTitleService.java` | 首轮 COMPLETED 后调度；有界线程池；超时 20s；输入截断 500+500；幂等 conversationId+turnId+revision |
| CAS 写标题 | `SqliteConversationStore.applyAutoTitle` | 仅 `title_source=AUTO`；同事务写 Outbox `TitleChanged` + FTS 刷新 |
| SSE 映射 | `ConversationSseController` | `TitleChanged` → `conversation.titleChanged` |
| 触发点 | `DurableTurnScheduler` / `TurnController` | `ExecuteTurnResult.Replied` 后 `scheduleAfterCompleted`（不阻塞 SSE/HTTP 终态） |
| 提示词 | `prompt-seeds/TITLE.md` | 最小标题专用；未做全仓提示词迁移 |
| Migration | — | 复用 V014 `title_source`；无 V016 |

### 前端

| 项 | 文件 |
|----|------|
| Stop / 撤队 API | `chat/api.js`（`cancelQueuedTurn` → 同 stop 路径） |
| 队列状态机 | `chat/queue.js` |
| composer 工具栏 | `chat/composer.js`（Stop / 正在停止… / COMMITTING 文案 / 撤队） |
| 标题守卫 | `chat/title.js`（MANUAL 忽略迟到 AUTO） |
| 接线 | `app.js` / `stream.js` / `state.js` / `sidebar.js` / `render.js` |
| 样式 | `wannian-ui/.../chat.css` |

## 不变量自检（实施者）

- [x] 前端不在未确认终态前显示「已停止」；Stop 接受后为「正在停止…」
- [x] COMMITTING → 「正在完成提交，无法停止」
- [x] RECEIVED 非 head → 「已排队」+ 可撤
- [x] rename 后本地 MANUAL；迟到 `titleChanged` AUTO 丢弃
- [x] 全 HTTP 经 `api.js`；selectionEpoch 门禁
- [x] 无新 `*Test.java`

## 审阅关注点（主会话）

1. 标题任务与主 Turn 解耦、失败保留临时标题是否足够。
2. Stop / 撤队与 C 路径一致性（同 `/stop`）。
3. A 未 `turn.started` 时连发 B 的队列可见性。
4. 双标签 / 切会话 epoch 是否串态。
