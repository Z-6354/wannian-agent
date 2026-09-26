# 0.2.4-E · followup / Stop / 自动标题阶段施工单

`status`: **代码已入仓 · 2026-09-24**（普通阶段门：直接入仓；无新测文件；待主会话 E 审阅）  
`upstream`: [已审范围](./k04-conversation-system-draft.md) · [系统设计](./k04-conversation-system-design.md) §4、§5.2、§7.2、§8、§11-E、§12、§13 · [工作流](../../version-stage-workflow.md)  
`alias`: K04-E  
`depends`: **0.2.4-C** · **0.2.4-D**  
`draft`: [`k04-e-draft/REVIEW.md`](./k04-e-draft/REVIEW.md)  
`note`: 本阶段**已包含**服务端首轮 AI 标题 Worker（C 未交付）；施工单旧「禁止改 Java」作废。

## 0. 执行者先读

本阶段交付 **调度交互 UI** + **首轮异步标题**：同会话 followup **排队可见**、**Stop** 运行中回合、**撤销**未执行排队项、**首轮异步标题**展示与 **MANUAL 覆盖守卫**；文案必须与服务端真实状态一致。

- **不做**：侧栏/历史/双层详情壳（D 已交付）；F 测试文件与真人；全仓提示词文件化（§13 / 路线图）；删除会话级联记忆。
- **硬约束**（设计 §5.2、§11-E）：**禁止**前端在未收到服务端终态（`turn.cancelled` / Stop API 成功且 status 确认 / `GET turn` 为终态）前显示「已停止」；COMMITTING 须显示「正在完成提交，无法停止」类真实文案。
- 生产代码在 `wn-server/` 与 `wannian-ui/`；标题 Worker 在 `app/title/`。
- 普通阶段 **不写测试文件**。

## 1. 已核实的代码事实

1. **followup 内核已有**（draft §2）：同会话串行 receive、RECEIVED 排队、取消 RECEIVED——在 kernel/`TurnCommitter` 路径；**页面无排队 UI**（`app.js` 在 `pending` 时拒发且整页禁用）。
2. **`TurnController`**（C 前）：POST 后同步 `execute`；响应 `HttpMapping.accepted` 可带 `CANCELLED` reasonCode，但 **无** Stop HTTP、**无** 撤队 HTTP、**无** SSE。
3. **`app.js`**：`submit()` 开头 `if (state.pending) return`——与 followup「A 运行时仍可 receive B」**相反**；E 须移除「单 pending 锁死发送」改为 **队列感知**（D 应已解除整页锁，E 补齐队列态）。
4. **B API**：`patchConversation(..., "rename", expectedRevision, title)` 成功 → `titleSource=MANUAL`（`SqliteConversationStore` CAS）；`ConversationSummaryBody.titleSource` 已到前端。自动标题 **不能** 用 PATCH 模拟，须等服务端事件或详情刷新。
5. **标题域**（设计 §4）：临时标题（「会话 N」）；仅 **首个成功完成** 的用户 Turn 触发一次异步生成；MANUAL 后迟到 auto 结果丢弃——**守卫在服务端 CAS**，前端须：rename 后本地 `titleSource=MANUAL`；收到 `titleChanged` 时若本地已是 MANUAL 且 revision 不匹配则 **忽略**。
6. **D 预留**（见 [D 施工单](./k04-d-chat-ui-implementation.md) §6）：`#composer-toolbar`、`.chat-actions[data-turn-id]`；E 只增控件不重构 transcript。
7. **`chat.css`**：`.chat-turn-status[data-active]` 脉冲点样式已有；可复用于「运行中/排队中」。

## 2. 不变量

1. **排队顺序**（设计 §5.2）：UI 顺序与持久 receive 顺序一致；**不以**点击先后或乐观插入顺序为准；展示 `turnId` 稳定列表（至少：运行中 1 + 队尾 N）。
2. **发送**：A 为 RUNNING/CLAIMED/COMMITTING 时，用户仍可发送 B；B receive 成功后 UI 显示 **「已排队」**（或等价），**不禁用** composer（空消息仍拒）。
3. **Stop**：仅对 **当前运行中** turnId 发 Stop；按钮在 RUNNING 可见，COMMITTING 禁用并 tooltip/文案说明；Stop 点击后 **「正在停止…」** 直至 SSE `turn.cancelled`/`turn.completed` 或轮询/status 确认终态。
4. **撤队**：仅对 `status=RECEIVED`（未 claim）项提供「撤销」；成功后从队列 UI 移除或标为已取消；**不**删除已持久 user message 的历史事实（设计：CANCELLED 仍审计）。
5. **终态文案**（draft §3.3、设计 §11-E）：

| 服务端事实 | 页面文案方向 |
|------------|--------------|
| RECEIVED 且非 head | 已排队 |
| RUNNING + Stop 已接受 | 正在停止… |
| COMMITTING | 正在完成提交，无法停止 |
| CANCELLED（Stop 或撤队） | 已取消（随服务端 detail） |
| FAILED | 失败 + 稳定码 |
| COMPLETED | 清除运行/排队指示 |

6. **标题**：listen `conversation.titleChanged`（C SSE）或 commit 后 debounce `getConversation`；更新侧栏 + 页眉 **仅当** `titleSource===AUTO` 或本地 revision 与服务端事件一致；用户 rename 后 **立即** 本地 MANUAL，忽略后续 auto 事件。
7. **标题失败**：无事件或 C 失败码 → **保留临时标题**；可选一次性 notice「标题生成失败，对话正常」；**不**阻塞输入、**不**每次打开重试（设计 §4.4）。
8. **selectionEpoch**：Stop/撤队/标题回调与 D 相同校验；旧会话队列不显示在当前 composer。
9. **归档/删除**：B 返回 `CONVERSATION_BUSY` 时，E 可强化文案「请先停止或等待队列完成」——不新增服务端逻辑。

## 3. 依赖 C 的前端契约（占位 · 以 C 施工单为准）

| 能力 | 假定形状（占位） | E 用途 |
|------|------------------|--------|
| 异步 receive | `accepted` + `turnId` + `status`（RECEIVED=排队） | 排队列表追加 |
| Stop | `POST /api/conversations/{cid}/turns/{turnId}/stop` 或 C 定稿 | 停止运行中 |
| 撤队 | `POST .../turns/{turnId}/cancel` 或 C 定稿；仅 RECEIVED | 移除排队项 |
| Turn 状态 | `GET .../turns/{turnId}` → `{ status, detail? }` | Stop/COMMITTING 校准 |
| SSE 终态 | `turn.cancelled`, `turn.completed`, `turn.failed` | 解除「正在停止」 |
| 标题事件 | `conversation.titleChanged` `{ conversationId, title, revision, titleSource }` | 侧栏/页眉更新 |

**Stop/撤队响应**须区分：`ok + 新 status`、`COMMITTING` 拒绝、`ALREADY_TERMINAL` 幂等——映射为 §2.5 文案，**禁止**一律 toast「已停止」。

## 4. 允许修改文件清单

| 序 | 文件 | 目的 |
|----|------|------|
| E1 | `wn-server/.../chat/api.js` | `stopTurn`、`cancelQueuedTurn`、`getTurnStatus`（封装 C 路径） |
| E2 | `wn-server/.../chat/queue.js`（新建） | 每会话 `{ runningTurnId, queued[], terminal{} }` 状态机 |
| E3 | `wn-server/.../chat/title.js`（新建） | titleChanged 处理、MANUAL 守卫、侧栏/页眉同步 |
| E4 | `wn-server/.../chat/composer.js`（新建） | toolbar：Stop、队列计数、发送逻辑与 queue 集成 |
| E5 | `wn-server/.../chat/app.js` | 挂接 queue/title/composer；移除遗留 pending 锁 |
| E6 | `wn-server/.../chat/stream.js` | 增终态/ title 事件分支（若 D 已建则 **增量**） |
| E7 | `wn-server/.../chat/sidebar.js` | 标题事件更新列表项；BUSY 提示文案 |
| E8 | `wannian-ui/.../layouts/chat.css` | 排队条、Stop/撤销按钮、队列列表样式 |
| E9 | `docs/plans/README.md` | 状态行 |

若 D 未拆模块，E 在对应单文件内增量，但须在草稿 MANIFEST 标明段落。

## 5. 禁止修改文件清单（实施时相对授权已修订）

原施工单禁止改 Java；**本轮用户授权废止该条**，允许并要求交付标题 Worker。仍禁止：

- 删除会话级联记忆 / 全仓提示词文件化 / 新 `*Test.java` / F 真人
- 改写旧 migration（V001–V015）；本阶段无需 V016（复用 `title_source`）
- D 已稳定的 DOM 根节点 id rename
- `manage/`、`shell/navigation.js`（非必要）

## 6. 分步实施顺序

### 6.1 总序（已完成）

1. 服务端标题 Worker + CAS + Outbox + SSE 映射  
2. **api.js Stop/撤队/status**（E1）→ **queue 状态机**（E2）→ **composer**（E4）→ **stream 终态**（E6）→ **title 守卫**（E3）→ **sidebar**（E7）→ **CSS**（E8）→ **app 接线**（E5）

### 6.2 分文件要点

**E1 · api.js**

- `stopTurn(conversationId, turnId, { signal })` → 解析 `result/reasonCode/detail/status`。
- `cancelQueuedTurn(conversationId, turnId, { signal })` → 仅 RECEIVED；冲突码映射 UI。
- `getTurnStatus(conversationId, turnId, { signal })` → Stop 后校准 COMMITTING。
- 路径常量顶部集中定义，注释「以 C 施工单为准」。

**E2 · queue.js**

- `onTurnAccepted({ turnId, status, replayed })`：replayed 不重复排队 UI。
- `onTurnStarted(turnId)`：head 移 running；启动超时 watchdog（可选，仅 UI 提示「长时间无响应」不调模型）。
- `onTurnTerminal(turnId, status)`：清 running；若队列非空显示「即将开始下一条」（下一条 start 由 SSE 驱动，**不**前端 POST 触发）。
- 暴露 `getRunningTurnId()`、`getQueuedTurnIds()`、`canStop()`、`canCancel(turnId)`。

**E4 · composer.js**

- Toolbar：`#stop-turn`（仅 `canStop()`）、`#queue-hint`（「另 N 条已排队」）。
- 发送：始终允许非空文本（同会话）；成功后交给 queue。
- Stop 点击：disable 按钮 + 文案「正在停止…」→ 调 E1 → 失败恢复按钮并显示 detail。
- 排队项列表（可选折叠）：每项 `撤销` → E1 cancel；仅 RECEIVED 显示。

**E3 · title.js**

- 注册 SSE handler：`conversation.titleChanged`。
- `applyTitleEvent(evt, localSummary)`：若 `localSummary.titleSource==='MANUAL'` 且 `evt.revision <= localSummary.revision` → drop；若 `evt.titleSource==='AUTO'` 且本地 MANUAL → drop。
- rename 成功回调：`markManualTitle(id, title, newRevision)` 立即更新 UI。
- 失败：listen C 可选 `conversation.titleFailed` 或超时无事件 → 一次性 notice（sessionStorage 标记已提示，防刷屏）。

**E6 · stream.js（增量）**

- `turn.*` 终态 → `queue.onTurnTerminal` + composer 刷新。
- 与 D 的 `applyStreamEvent` 顺序：先 queue 后 render，避免 Stop 后仍显示 running pulse。

**E7 · sidebar.js（增量）**

- 标题变更：更新对应 `li[data-conversation-id]` 与当前页眉（若 active）。
- `CONVERSATION_BUSY`：统一 notice 模板。

**E8 · chat.css**

- `.composer-toolbar` 内 Stop primary/secondary 尺寸与 composer 胶囊对齐。
- `.chat-queue-list`、`.chat-queue-item`、撤销 ghost 按钮。
- `[data-turn-status="queued"]` 弱化样式 vs `[data-active]`。

## 7. HTTP / 事件依赖矩阵

| 来源 | 触发 | UI 更新 |
|------|------|---------|
| C async receive | 用户发送 | queue 追加；hint 更新 |
| C SSE `turn.started` | 调度 | running 指示；队首移出 queued 视觉 |
| C SSE `turn.cancelled/completed/failed` | 终态 | 清 Stop 态；可能下一条 started |
| C Stop API | 用户点 Stop | 等待 SSE；COMMITTING 即时文案 |
| C cancel API | 撤销排队 | 移除项 |
| C `conversation.titleChanged` | 首轮完成後 | 条件更新 title |
| B PATCH rename | 用户改名 | 立即 MANUAL + 忽略 auto |

## 8. 验收（本阶段 · 行为 · 无测文件）

- [ ] A 生成中发送 B：B 用户消息可见且标 **已排队**；A 完成后 B 自动开始（观察 SSE，无需刷新）。
- [ ] Stop：运行中点击 → 「正在停止…」→ 服务端 cancelled 后 UI 终态；**无**未确认「已停止」。
- [ ] COMMITTING 时 Stop 不可用且文案说明无法停止。
- [ ] 撤销：仅排队项可撤；撤后不再执行；当前 running 不受影响。
- [ ] 重复 Stop/撤队幂等：不报错栈；UI 不闪烁 duplicate。
- [ ] 首轮完成后自动标题更新侧栏/页眉（AUTO）；手动改名后 auto 事件 **不** 覆盖。
- [ ] 标题失败：临时标题保留；对话与发送正常。
- [ ] 切换会话：队列/Stop 态不串到另一会话。
- [ ] 全 HTTP 仍仅经 `api.js`。
- [ ] 未写测试文件。

## 9. 与下一阶段（F）交接

**F 须覆盖的本阶段路径**（设计 §11-F、§10 矩阵）：

- A 运行时连发 B/C 有序；Stop 与完成提交竞速；COMMITTING 边界。
- 自动标题 vs 手动 rename 竞态（MANUAL 胜出）。
- 双标签同会话：UI 状态与 SSE 终态一致（不要求 E 实现多端同步，但不得本地假终态）。
- 归档/删除 vs 排队 BUSY 提示。

**交付物**：E 阶段代码审核勾选 §8；F 补 JavaScript 窄测（若项目引入 front test）+ 真人 live「正文增量 + 工具 + Stop/followup + 重开 + 自动标题/改名」。

## 10. 开工门

- [x] **D 已交付** composer/turn 挂点与 SSE 接线。
- [x] **C** 已提供 Stop/撤队/turn 状态/SSE；标题 Worker 由 **E 补齐**。
- [x] 标题 SSE：`TitleChanged` → `conversation.titleChanged`。

**测试策略**：普通阶段 **不写测试文件**；§8 供 F 自动化与真人验收。

## 11. 服务端标题任务（已交付）

1. 触发：首个 **成功完成** Turn（`ExecuteTurnResult.Replied`）后异步任务（设计 §4.1）；不阻塞主回复 / SSE 终态。
2. 输入：首条 user text + 首轮 assistant 有限片段；`TimeoutModelPort` 20s；无工具；提示词 `prompt-seeds/TITLE.md`。
3. 写入 CAS：`title_source=AUTO`、revision 匹配、会话非 TRASHED；幂等 conversationId+turnId+revision。
4. 发布：Outbox `TitleChanged` → SSE `conversation.titleChanged`。
5. 失败：保留临时标题；不反复每次打开重试。
