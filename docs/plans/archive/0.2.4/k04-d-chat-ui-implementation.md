# 0.2.4-D · 网页会话系统阶段施工单

`status`: **代码已入仓** — 2026-09-24  
`upstream`: [已审范围](./k04-conversation-system-draft.md) · [系统设计](./k04-conversation-system-design.md) §5.2、§7.3、§8、§11-D、§12 · [工作流](../../version-stage-workflow.md)  
`alias`: K04-D  
`depends`: **0.2.4-B**（会话读写 API 已入仓）· **0.2.4-C**（异步 receive、SSE/补发、运行中事件；路径与 DTO **以 C 施工单为准**，本单只写前端接线假设）  
`blocks`: **0.2.4-E**（followup/Stop/标题 UI 需 D 的 composer 挂点、turn 行、侧栏标题位）

## 0. 执行者先读

本阶段只交付 **`/chat/` 页面**：侧栏与会话生命周期 UI、打开时最近会话恢复、已提交历史回读、**C 提供的 SSE 流式正文与工具过程**、回合/工具双层可展开详情、**会话切换代号**防串线。

- **不做**：followup 排队条、Stop/撤队按钮与文案、首轮 AI 自动标题消费与 MANUAL 守卫（属 **E**）；服务端 Java、migration、Outbox Publisher（属 **C** 及以前）；测试文件与真人验收（属 **F**）。
- **不挡 E**：D 须在 composer / 每条 assistant-turn 区域预留 **toolbar 挂点**（见 §6 D8），E 只补控件与逻辑，不重排 DOM。
- 生产文件先写 `docs/plans/archive/0.2.4/k04-d-draft/`，逐文件审过再拷入 `META-INF/resources/chat/` 与 `wannian-ui/.../chat.css`。普通阶段**不写、不审、不要求**新测试类。
- **禁止**在 `app.js` 或其它视图模块内直接 `fetch`；所有 HTTP/SSE 经 `api.js`（设计 §8、§11-D）。

## 1. 已核实的代码事实

1. **`index.html`**：`#app-sidebar` 现为全站主导航（`navigation.js`），**无**会话列表 DOM；`#chat-status` 展示原始 `conversationId` 字符串；无搜索/归档/回收站入口。
2. **`app.js`**（约 267 行）：`state` 仅 `{ conversationId, messages[], pending, notice }`；`sessionStorage` 键 `wannian.chat.conversationId`；**不**调用 B 的 list/recent/history/search/patch。
3. **`app.js` 发送路径**：`submit()` → `ensureConversationAndSend()` → `sendTurn()`；`state.pending === true` 时**整页禁用**输入与新会话；假定 **同步** HTTP 一次返回 `reply` + `toolCalls`（与当前 `TurnController` 一致）。
4. **`app.js` 渲染**：扁平 `ol.chat-list`；工具与助手各一条 `p.chat-text`；**无** turn 外层折叠、**无**工具内层折叠、**无** `aria-expanded`；`transcript.scrollTop = scrollHeight` **每次全量 render 强制滚底**（与用户上翻/展开详情冲突，设计 §7.3 禁止）。
5. **`api.js`**：已封装 B 全部 REST（`listConversations`、`recentConversation`、`listMessages`、`searchConversations`、`patchConversation`、`emptyTrash`）；`sendTurn` 仍 POST 同步路径；**无** SSE、`AbortController`、事件订阅封装；文件头注释已标明「布局仍由 D 接入」。
6. **B HTTP 已入仓**（`ConversationController`）：列表默认 ACTIVE + cursor；`GET /recent` 无会话时 **204**（`api.js` 已 normalize 为空体）；历史 `GET .../messages?afterSeq&limit&includeToolCalls` 返回 `MessageBody{id, role, text, sequenceNo, turnId, createdAt, toolCalls?}`；详情含 `titleSource`（`AUTO`|`MANUAL`）、`revision`。
7. **`ToolCallView`**（A 安全投影）：`name, startedAt, finishedAt, argumentsJson, status, errorCode`；历史与实时须同一投影语义（设计 §6.2）。
8. **`chat.css`**：已有消息气泡、composer 胶囊、**预留**类名 `.chat-actions`、`.chat-turn-status`、`.composer-toolbar`（注释 “Forward chat surfaces”）；**无**侧栏会话列表、turn/tool `<details>` 样式。
9. **`TurnController`**（现状，C 前）：POST receive 后仍同步 `turnEngine.execute`；**不发 SSE**。D 开发可先用 C 草稿/stub 或 feature flag 接 SSE；不得为实现 D 而改 `wn-server` Java。

## 2. 不变量

1. **三层页面状态**（设计 §7.3）：① 已提交历史（Message + 公开 step 投影）；② 当前 execution 的临时投影（delta/运行中工具）；③ 当前选中会话 + **选择代号** `selectionEpoch`（单调递增整数）。所有异步回调渲染前必须校验 `conversationId` 与 `selectionEpoch` 仍匹配。
2. **合并键**：Message 用 `messageId`；Turn 运行块用 `turnId`；工具卡用 `turnId + callId`（或 C 事件载荷中等价稳定 id）；Outbox 持久事件用 `sequenceNo` 去重；运行中事件用 `(executionId, runSeq)` 去重。
3. **流式合并**：`reply.delta` 只追加到对应 `executionId` 的临时助手正文；`message.committed` / 历史拉取到达后 **用正式 Message 替换** 临时块并清除该 execution 临时态；失败/取消终态须标注未完成，**不得**写入已提交历史数组。
4. **切换会话**：递增 `selectionEpoch`；`AbortController`  abort 进行中的 history/search fetch；关闭/丢弃旧 SSE 订阅；旧响应只写入 **按 conversationId 索引的侧栏缓存**（可选），**禁止**写入当前 transcript DOM。
5. **打开页恢复**：`GET /api/conversations/recent` → 有则 `getConversation` + 分页拉历史（`afterSeq` 递增直到无 `nextAfterSeq`）；204/404 则空态 + 「新会话」；**零**模型/工具调用（draft §3.2）。
6. **侧栏列表**：默认 `status=ACTIVE`，按 B 稳定排序；选中行与 `state.conversationId`、页眉标题同步；TRASHED 不出现在默认列表/搜索（B 已保证）。
7. **生命周期 UI**：改名/归档/取消归档/移入回收站/恢复/清空回收站只调 B PATCH/POST；须带 `expectedRevision`；`CONVERSATION_BUSY` / `REVISION_CONFLICT` 显示服务端 `detail`，不静默失败。
8. **安全渲染**：用户/模型/工具文本用 `textContent` 或受控 Markdown（若引入须限链接/HTML）；**禁止** `innerHTML` 直插工具输出（设计 §9）。
9. **滚动**：维护「用户是否在底部附近」；仅附近时跟随新 token/新消息滚底；展开详情或上翻时不强制 `scrollTop = scrollHeight`。
10. **详情双层**（设计 §6.2、draft §3.1）：外层 **回合过程**（按序：模型片段摘要、工具列表、结束）；内层 **单工具** `<button aria-expanded>` + 安全参数/结果/耗时/错误码；展开状态 Map  keyed by stable id，**增量 patch DOM 时不重置** expanded。
11. **D 不宣称 Stop/排队终态**：运行中仅显示 C 事件或服务端 turn 状态允许的中性文案（如「生成中」）；**不**实现「已停止」「已排队」的最终 UX（E）；不在未收到终态事件前移除 Stop 所需 turn 行。

## 3. 依赖 C 的前端契约（占位 · 以 C 施工单为准）

实施 D 前须从 **C 施工单** 抄定下列能力；路径/DTO 不一致时 **以 C 为准**，只改 `api.js` 封装，不改 DOM 合并规则。

| 能力 | 假定形状（占位） | D 用途 |
|------|------------------|--------|
| 异步 receive | `POST .../turns` 快速返回 `{ result:"accepted", turnId, status:"RECEIVED"\|"RUNNING", replayed }`，**无** `reply` | 发送后立即订阅 SSE，不再 `state.pending` 锁死整页 |
| SSE 订阅 | `GET /api/conversations/{id}/events?cursor=` 或 C 定稿路径；`fetch` + `ReadableStream` 解析 `text/event-stream` | 唯一流式入口；须可带与 REST 一致的鉴权头 |
| 运行中事件 | `turn.started`, `reply.delta`, `tool.started`, `tool.updated` | 临时投影 + 工具卡更新 |
| 已提交事件 | `message.committed`, `turn.completed` / `failed` / `cancelled` | 替换临时态、更新侧栏 `lastActivity` |
| Turn 状态查询（可选） | `GET .../turns/{turnId}` → `status` | SSE 重连间隙校准 |
| 持久 cursor | SSE `id:` 或载荷 `sequenceNo` | 重连补发；重复 sequence 不双插 |

**C 未就绪时**：D 可先完成侧栏/历史/静态详情壳，用 **feature flag**（如 `?sync=1` 或 localStorage）回退现有同步 `sendTurn`；**不得**为 D 修改 `TurnController` 同步语义。

## 4. 允许修改文件清单

| 序 | 文件 | 目的 |
|----|------|------|
| D1 | `wn-server/.../chat/api.js` | SSE 订阅、异步 send 封装、AbortSignal 透传；仍唯一网络出口 |
| D2 | `wn-server/.../chat/app.js` | 入口：bootstrap、路由级 state、挂接各模块 |
| D3 | `wn-server/.../chat/state.js`（新建） | `selectionEpoch`、按会话 cache、合并 reducer |
| D4 | `wn-server/.../chat/sidebar.js`（新建） | 列表/搜索/归档/回收站/改名 UI |
| D5 | `wn-server/.../chat/history.js`（新建） | 分页拉历史 → 已提交层 |
| D6 | `wn-server/.../chat/stream.js`（新建） | SSE 解析、运行中投影、与 reducer 对接 |
| D7 | `wn-server/.../chat/render.js`（新建） | transcript 增量 DOM、双层 details、滚动策略 |
| D8 | `wn-server/.../chat/index.html` | 侧栏会话区、页眉标题、搜索/生命周期控件、composer toolbar 挂点 |
| D9 | `wannian-ui/.../layouts/chat.css` | 侧栏列表、turn/tool details、流式态、窄屏 |
| D10 | `wannian-ui/.../layouts/app-shell.css`（**仅** chat 侧栏宽度/栅格微调） | 双栏 shell；改动须最小 |
| D11 | `docs/plans/README.md` | 状态行 |

模块拆分 D3–D7 为建议；若保持单文件 `app.js` 须在施工草稿说明理由且单文件 ≤ 审阅可接受上限。**新增 chat 下 `.js` 须列入 MANIFEST 后再写。**

## 5. 禁止修改文件清单

- **全部** `wn-server/**/*.java`（含 `TurnController`、`ConversationController`、ModelPort、Outbox、Journal）
- `wn-server/**/db/migration/**`
- `chat/` 以外的前端（`manage/`、`shell/` 除 D10 允许的 app-shell 微调）
- 任意 `**/test/**`、`**/*Test.java`
- `docs/plans/archive/0.2.4/k04-e-*`（E 另开单）
- Prompt/Skill 种子与 P 阶段文件

## 6. 分步实施顺序

### 6.1 总序

1. **api.js 客户端层**（D1）→ 2. **state/reducer**（D3）→ 3. **index 结构**（D8）→ 4. **侧栏 + 生命周期**（D4）→ 5. **历史恢复**（D5）→ 6. **CSS**（D9/D10）→ 7. **流式 + 双层详情**（D6/D7）→ 8. **app 入口接线**（D2）→ 9. **文档指针**（D11）

### 6.2 分文件要点

**D1 · api.js**

- 导出 `subscribeConversationEvents(conversationId, { cursor, signal, onEvent, onError })`：`fetch` + stream reader；解析 `event:` / `data:` / `id:`；网络错误映射为与现有 `failed()` 一致结构。
- 导出 `sendTurnAsync(...)` 或扩展 `sendTurn`：识别 `result:"accepted"` 且无 `reply` 的成功态；保留对旧同步响应的兼容解析（C 过渡期）。
- 所有 GET/PATCH/POST 增加可选 `{ signal }` 供切换会话 abort。
- **禁止**其它 chat 模块出现 `fetch(`。

**D3 · state.js**

- `createChatState()`：`selectionEpoch`、`activeConversationId`、`conversationsById`（侧栏缓存）、`committedByConversation`、`inflightByTurnId`、`expandedTurnIds`、`expandedToolIds`、`scrollPinnedBottom`。
- `selectConversation(id)`：`epoch++`，返回 abort 需触发的 cleanup 列表。
- Reducer 纯函数：`applyHistoryPage`、`applyStreamEvent`、`applyCommittedMessage`；内含 sequence / `(executionId,runSeq)` 去重。

**D8 · index.html**

- 在 `chat-workspace` 内或并列增加 **会话侧栏** `#chat-conversation-sidebar`（与全站 `#app-sidebar` 区分）：搜索框、列表 `#conversation-list`、底部分区入口（归档/回收站）。
- 页眉：`#conversation-title` 替代 `#chat-status` 展示标题；副标题可保留短 id 或隐藏。
- `#transcript` 保留；composer 内增加 `<div class="composer-toolbar" id="composer-toolbar">` **空挂点**（E 放 Stop/排队）。
- 每条 assistant turn 容器预留 `<div class="chat-actions" data-turn-id>`（E 挂 Stop）。

**D4 · sidebar.js**

- 启动：`listConversations("ACTIVE")` 填列表；点击行 → `selectConversation` + 拉详情/历史。
- 搜索：debounce 调 `searchConversations(q)`；展示 snippet；点击打开并可选 `scrollToMessage(messageId)`（D7 实现锚点）。
- 新建：`createConversation` → 选中 → 空 transcript。
- 改名：inline 或 modal → `patchConversation(id,"rename",revision,title)`；成功后更新页眉 + 列表项 `titleSource=MANUAL` 本地标记（**E 负责** auto title 事件；D 只显示 PATCH 结果）。
- 归档/取消归档/移入回收站/恢复：确认对话框 + PATCH；`CONVERSATION_BUSY` 提示先完成或停止（文案可中性，详细 Stop 指引在 E）。
- 回收站视图：`listConversations("TRASHED")` + restore；`emptyTrash("EMPTY_TRASH")` 二次确认「不可恢复」。
- 窄屏：会话侧栏可折叠（复用 mobile shell 模式或独立 toggle），须键盘可达。

**D5 · history.js**

- `loadFullHistory(conversationId, signal)`：`afterSeq` 循环直到无 `nextAfterSeq`；默认 `includeToolCalls=true`。
- 映射为 turn 分组结构：user message → assistant turn block（含 `toolCalls[]`）供 D7 渲染。
- Bootstrap：`recentConversation()` → 204 则空态；否则 load。

**D6 · stream.js**

- 选中会话且存在 active turn 时建立 SSE；切换/`epoch` 变化 teardown。
- 事件处理器表驱动；未知 event type 忽略并 `console.debug`（不 throw）。
- 断线：指数退避重连（有界次数）；重连时带最后持久 cursor；缺口时 C 若要求 **拉 history + turn status** 则调用 D5/D1。

**D7 · render.js**

- **增量 render**：按 turnId 定位 DOM 节点；delta 只更新对应 `.chat-text` 文本节点。
- 外层：`<details class="chat-turn-process">` 或 button+panel，含步骤时间线。
- 内层：每个 tool 一条 `<details class="chat-tool-card">`；参数/结果用 `prettyJson` 等价逻辑但 **textContent**。
- `renderTranscript(state, { forceScroll })` 读 `scrollPinnedBottom`；监听 transcript `scroll` 更新 pin 状态。
- 空态文案改为「恢复最近会话或新建」；删除「刷新后不回放历史」。

**D2 · app.js**

- 薄入口：import 模块、绑定 composer submit、wire sidebar、初始 bootstrap。
- Submit（D 版）：异步 receive 成功后 **不禁用** composer（E 才展示排队）；D 仅禁止空文本重复 submit 到同一空 turn 的可接受策略由 E 细化——D 最小要求：**不因单 turn 运行中锁死整页**（与现 `state.pending` 相反）。

## 7. HTTP / 事件依赖矩阵

| 来源 | 消费点 | 失败表现 |
|------|--------|----------|
| B `GET /recent` | bootstrap | 空态，不 500 |
| B `GET /messages` | history.js | banner 错误；不清已有 committed |
| B `GET/PATCH/search/emptyTrash` | sidebar.js | 列表保持 + detail |
| C async `POST /turns` | submit | 无 reply 时仍追加 user 消息到 UI（已提交 receive 事实） |
| C SSE 运行中 | stream.js → render | 断线 banner「连接中断，正在重试」 |
| C SSE 已提交 | stream.js → state | 去重后合并；触发侧栏 resort（可选 debounce） |

## 8. 验收（本阶段 · 行为 · 无测文件）

- [x] 打开 `/chat/` 自动加载 recent ACTIVE 会话历史；无会话时空态。
- [x] 侧栏列表、搜索、新建、改名、归档、回收站/恢复/清空可用且调 B API；revision 冲突有提示。
- [x] 页眉显示会话 **标题** 而非裸 UUID。
- [x] 切换会话后旧 history/SSE 回调 **不** 改当前 transcript（手动：快速切换 A/B 并延迟响应）。
- [x] 流式正文增量可见；工具过程可展开且展开状态在 delta 下保持。
- [x] 提交完成后临时助手块被正式 Message 替换；刷新后与服务端历史一致。
- [x] 上翻时新 token **不** 强制滚底。
- [x] 全页 **无** 散落 `fetch`；仅 `api.js`。
- [x] composer toolbar / turn chat-actions 挂点存在且 E 可接。
- [x] 未写测试文件；现有编译/窄测不被故意破坏。

> 上表勾选表示本阶段**代码已按清单实现**；真人/自动化验收仍属 **0.2.4-F**。

## 9. 与下一阶段（E）交接

**交给 E：**

- `#composer-toolbar`、`[data-turn-id].chat-actions` DOM 与 CSS 类名稳定约定。
- `state.inflightByTurnId` / `queuedTurnIds` 扩展位（D 的 reducer 预留 `queue[]` 字段或 E 增字段）。
- 侧栏项 `{ id, title, titleSource, revision }` 与页眉 `#conversation-title` 更新函数 `setConversationHeader(summary)`。
- SSE 已接入时，E 监听 `conversation.titleChanged`（C 定义）并调 `updateSidebarTitle`；D **不** 实现该 listener。
- 同步→异步 send 已通；E 添加 Stop/撤队 API 封装到 `api.js`（E 改 D1 时须审）。

**E 不需要重做：**

- 侧栏布局、历史分页、双层 details 壳、selectionEpoch 机制、B 生命周期表单。

## 10. 开工门

- [ ] 用户确认本 MANIFEST 与 C 契约占位。
- [ ] C 施工单已发布或 D 明确 sync 回退策略。
- 下一步：`docs/plans/archive/0.2.4/k04-d-draft/` 按 D1→D3→D8→… 顺序草稿；**禁止**改 `wn-server` Java。

**测试策略**：普通阶段 **不写测试文件**；§8 行为清单供阶段代码审核与 **0.2.4-F** 自动化/真人验收复用。
