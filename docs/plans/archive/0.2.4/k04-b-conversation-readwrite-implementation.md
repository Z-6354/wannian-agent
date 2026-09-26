# 0.2.4-B · 会话读写阶段施工单

`status`: **代码已入仓 · 2026-09-24**（B API/仓储/V014；页面侧栏属 D）  
`upstream`: [已审范围](./k04-conversation-system-draft.md) · [系统设计](./k04-conversation-system-design.md) · [工作流](../../version-stage-workflow.md)  
`alias`: K04-B  
`depends`: **0.2.4-A**（安全投影 / turn_step）已交付；**不依赖** P，可与 P 交错但本单不改提示词/Skill
`draft`: `docs/plans/archive/0.2.4/k04-b-draft/`（对照用；生产已在 `wn-server/`）  
`review`: 核心已通过；HTTP 随「下一阶段」一并入仓

## 0. 执行者先读

本阶段只交付 **会话目录 + 只读历史/过程投影 + 生命周期 HTTP API**（列表、最近、详情、历史、搜索、改名、归档、回收站/恢复/清空）。

- **不做**：SSE / Outbox 发布、模型流式、异步 receive、Stop/followup UI、AI 自动标题、`/chat/` 侧栏与布局（属 C/D/E）。
- **不挡** C：本阶段可扩 `ConversationStore` / migration / HTTP；禁止改 `TurnEngine` 主路径、模型适配器、Outbox Publisher。
- 生产文件先写 `docs/plans/archive/0.2.4/k04-b-draft/`，逐文件审过再入 `wn-server/`。普通阶段不要求新测文件；禁止故意破坏现有编译。
- 删除会话 **不等于** 遗忘长期记忆；清空回收站不得级联删 `memory_record`。

## 1. 已核实的代码事实

1. `ConversationStore` 仅 `create` + `listRecentMessages`；无 list/update/search。HTTP 仅 `POST /api/conversations`。
2. `ConversationStatus` 仅 `ACTIVE`；表已有 `revision`、`last_activity_at`（V008）、`title`。
3. `TurnRepository` 仅按 `TurnId` 读写，**无**「按会话查进行中 Turn」；归档/回收站门禁需新增有界查询。
4. 消息 `content_json` envelope 形如 `{"v":1,"text":"..."}`；Assembler 已按 `text` 解析。搜索必须抽 `text`，禁止把整段 JSON / 隐藏推理当可搜正文。
5. 公开工具过程投影复用 `TurnToolCallProjector`（A 已脱敏）；历史 API 不得返回原始 `turn_step.request_json`。
6. 最高 migration：**V013**；本阶段从 **V014** 起追加，禁止改 V001–V013。
7. `/chat/` 仅 `sessionStorage` 存 `wannian.chat.conversationId`；刷新不回放历史。页面接线属 D，本阶段最多给 `api.js` 增加只读/管理 HTTP 包装（无布局）。

## 2. 不变量

1. **状态机**（与设计 §3.2 一致；CAS 用 `expectedRevision`）：

| 操作 | 合法源 | 结果 | 额外门禁 |
|------|--------|------|----------|
| 手动改名 | ACTIVE / ARCHIVED | 同状态；`title_source=MANUAL`；`revision+1` | 标题非空、≤80 字、去控制字符 |
| 归档 | ACTIVE → ARCHIVED | `revision+1` | 会话无进行中/排队 Turn，否则拒绝 |
| 取消归档 | ARCHIVED → ACTIVE | `revision+1` | 无 |
| 移入回收站 | ACTIVE/ARCHIVED → TRASHED | 记 `pre_trash_status`、`trashed_at`；`revision+1` | 同上进行中门禁 |
| 恢复 | TRASHED → `pre_trash_status` | 清 `trashed_at`；`revision+1` | CAS；不清空与恢复竞态靠 CAS |
| 清空回收站 | 仅 TRASHED 且无活动 Turn | 物理删除会话树 | 有界批次；不删跨会话 Memory |

2. **进行中 Turn** = 该会话存在 `turn.status ∈ {RECEIVED, CLAIMED, RUNNING, COMMITTING}`。门禁与 receive 共用同一查询口径（SQL 有界 COUNT）。
3. **列表默认**仅 `ACTIVE`，按 `COALESCE(last_activity_at, created_at) DESC, id DESC` 稳定分页（cursor = 上一页末条的排序键）。
4. **最近会话** = 默认列表第一条（无可恢复则 empty）；**只读**，零模型/工具调用。
5. **历史**只返回已提交 Message + 经 `TurnToolCallProjector` 的公开工具摘要；`limit` 硬顶（建议默认 50、最大 100）。
6. **搜索**：只搜标题 + 已提交消息 `text`；默认排除 TRASHED；可筛 ACTIVE/ARCHIVED；空 query → 退回列表语义，不扫 FTS；有界 `limit`；不调用模型。
7. **ARCHIVED / TRASHED 拒绝新 Turn**：在 `TurnCommitter.receive`（或等价入口）校验会话状态；本阶段必须接线，避免回收站仍收消息。
8. kernel 不读 JDBC 细节；app 实现仓储；Controller 只做 DTO/错误映射。

## 3. Migration（V014）决议

文件：`wn-server/app/src/main/resources/db/migration/V014__conversation_lifecycle.sql`

```text
conversation 追加：
  title_source     TEXT NOT NULL DEFAULT 'AUTO'   -- AUTO | MANUAL
  pre_trash_status TEXT NULL                      -- 进回收站前 ACTIVE/ARCHIVED
  trashed_at       TEXT NULL

-- status 仍为 TEXT；领域枚举扩 ARCHIVED/TRASHED（不做破坏性 CHECK，避免旧库锁死）

索引建议：
  (status, last_activity_at DESC, id DESC)   -- 列表
  (status, trashed_at)                       -- 回收站

FTS5（推荐主路径）：
  conversation_search(
    conversation_id UNINDEXED,
    message_id UNINDEXED,
    title,
    body,
    status UNINDEXED
  )
  -- 回填：用现有 message + conversation 生成行；正文只取 envelope.text
  -- 增量：message 正式 INSERT 后由 SqliteTurnCommitter / ConversationStore 同步写入
  -- 改名：更新该会话 title 相关 FTS 行或重建该 conversation_id 的 title 字段
  -- 删会话：DELETE FROM conversation_search WHERE conversation_id=?
```

若运行时创建 FTS5 失败（极罕见）：施工草稿须给出 **有界 LIKE 回退**（全局最多扫描 N 会话 × 每会话近 M 条消息，N/M 写死进 yml，禁止无界 `LIKE %` 全表入堆）。默认以 FTS5 验收。

清空回收站删除顺序（同事务、有界批次，建议每批 ≤20 会话）：

1. 确认无活动 Turn  
2. 删 `conversation_search`  
3. 删该会话 `turn_step`（按 conversation_id / turn_id）  
4. 删冻结计划（若有会话维度）  
5. 删 `outbox` 中仅属该会话的事件（若可按 conversation_id 过滤；否则保留孤儿并文档化，**禁止**无界扫全表）  
6. 删 `turn`、`message`  
7. 删 `conversation`  

Memory / Relationship **不删**。若某步引用不清，草稿必须画依赖图并回报，不得臆造 CASCADE。

## 4. HTTP 契约（本阶段）

基路径沿用 `/api/conversations`。鉴权与现网 `POST` 创建一致（本机回环聊天路径现状）；**不在本阶段扩大成第二套口令模型**。全站统一 `ManageAuthFilter` 覆盖聊天属设计 §9 的 0.2.4 总要求——若本阶段改动面过大，记交接给 C 前必须补齐，不得宣称 B 可外网裸奔。

| 方法 | 路径 | 要点 |
|------|------|------|
| GET | `/api/conversations` | `status`（默认 ACTIVE）、`cursor`、`limit` → 摘要列表 + `nextCursor` |
| GET | `/api/conversations/recent` | 最近一条 ACTIVE；无则 204/空体约定在草稿钉死 |
| GET | `/api/conversations/{id}` | 详情：title/status/revision/titleSource/lastActivity；TRASHED 可读（回收站 UI 用）；不存在 404 |
| GET | `/api/conversations/{id}/messages` | `afterSeq`/`beforeSeq`/`limit` → 消息（公开 text）+ 可选 `toolCalls`（按 turn 投影） |
| GET | `/api/conversations/search` | `q`、`status`、`cursor`、`limit` → 命中会话 + snippet + messageId |
| PATCH | `/api/conversations/{id}` | body：`op` + `expectedRevision` + 字段；`op=rename\|archive\|unarchive\|trash\|restore` |
| POST | `/api/conversations/trash/empty` | 明确确认字段（如 `confirm=EMPTY_TRASH`）；返回删除数 |

错误码：沿用 `ErrorCodes`；新增集中定义（如 `CONVERSATION_BUSY`、`REVISION_CONFLICT`、`INVALID_TITLE`、`NOT_TRASHED`）。冲突返回可行动文案，不堆栈。

**本阶段改 `TurnCommitter.receive`**：非 ACTIVE 会话拒绝新 Turn（稳定错误码）。

## 5. MANIFEST 与审序

草稿根：`docs/plans/archive/0.2.4/k04-b-draft/`。一次只推审阅批次；**核心未通过不得入仓**。

### 5.1 全量 14 文件

| 序 | 档 | 文件 | 目的 |
|----|----|------|------|
| B1 | 轻 | `api/.../ConversationStatus.java` | +ARCHIVED/TRASHED |
| B2 | 轻 | `api/.../TitleSource.java` | AUTO/MANUAL |
| B3 | **核** | `db/migration/V014__conversation_lifecycle.sql` | 列/索引/FTS/回填 |
| B4 | **核** | `kernel/.../ConversationStore.java` + 同包命令/结果类型 | 仓储口扩展 |
| B5 | **核** | `app/.../SqliteConversationStore.java` | 列表/CAS/生命周期/搜索 |
| B6 | **核** | `app/.../SqliteConversationActivity.java` | hasActiveTurn |
| B7 | **核** | `app/.../SqliteTurnCommitter.java`（增量） | ACTIVE 门禁 + FTS 同步 |
| B8 | 轻 | `kernel/.../ErrorCodes.java`（增量） | CONVERSATION_BUSY 等 |
| B9 | **核** | `app/.../http/ConversationController.java` + HTTP DTO | REST 契约 |
| B10 | 中 | `app/.../http/ConversationHistoryAssembler.java` | 消息+工具投影 |
| B11 | 轻 | `app/.../http/HttpMapping.java`（增量） | 结果映射 |
| B12 | 轻 | `app/.../persistence` insert 列对齐（并入 B5） | create 写 title_source |
| B13 | 轻 | `chat/api.js` | 只加 fetch，不改布局 |
| B14 | 轻 | `docs/plans/README.md` / roadmap 状态行 | 文档指针 |

**核心 6（须你审）**：B3、B4、B5、B6、B7、B9。  
**可快速扫**：B1、B2、B8、B10、B11、B13。

### 5.2 原简表（对照）

| 序 | 文件 | 目的 |
|----|------|------|
| B1 | `api/.../ConversationStatus.java` | 加 `ARCHIVED`、`TRASHED` |
| B2 | `api` 或 `kernel` 值类型：`TitleSource`、列表/搜索 DTO 命令结果（按现有风格） | 领域结果封闭类型 |
| B3 | `db/migration/V014__conversation_lifecycle.sql` | 列、索引、FTS、回填 |
| B4 | `kernel/.../ConversationStore.java`（及命令/结果类型） | 扩展 list/get/rename/生命周期/search/emptyTrash；保留 create/listRecentMessages |
| B5 | `app/.../SqliteConversationStore.java`（可拆 `SqliteConversationSearch` / `SqliteConversationLifecycle` 若单文件过大） | SQLite 实现 + CAS |
| B6 | `app/.../SqliteTurnRepository.java` 或新建 `SqliteConversationActivity.java` | `hasActiveTurn(conversationId)` |
| B7 | `app/.../SqliteTurnCommitter.java` | receive 校验 ACTIVE；消息提交后同步 FTS（最小挂钩） |
| B8 | `app/.../http/*`：列表/详情/历史/搜索/PATCH/empty DTOs + Controller 方法 | 可扩展现有 `ConversationController` 或拆 `ConversationQueryController`；禁止改 Turn 同步执行语义 |
| B9 | `app/.../http` 历史组装：Message 公开投影 + 复用 `TurnToolCallProjector` | 只读 |
| B10 | `chat/api.js` | **仅**增加对应 fetch 包装；**禁止**改 `app.js` 布局/侧栏 |

**禁止触及**：V001–V013 原文、`TurnEngine`/`DefaultAgentLoop`、ModelPort 流式、Outbox Publisher/SSE、`chat/app.js` / `index.html` 布局、Memory Policy/Review、Prompt/Skill（P）、BackgroundTask。

## 6. 验收（本阶段 · 代码行为）

- [ ] `GET` 列表默认仅 ACTIVE，分页稳定不重不漏。
- [ ] `GET /recent` 只读；无会话不报 500。
- [ ] 历史回读零模型调用；工具字段经安全投影。
- [ ] 改名 CAS：错误 revision → 冲突；成功后 `title_source=MANUAL`。
- [ ] 有 RUNNING/RECEIVED Turn 时 archive/trash → `CONVERSATION_BUSY`。
- [ ] TRASHED 不出现在默认列表/recent/默认搜索；可 restore。
- [ ] empty trash 有确认令；不删 memory；有活动 Turn 的会话跳过或整批拒绝（草稿钉一种并测）。
- [ ] 非 ACTIVE 会话 `receive` Turn 被拒。
- [ ] 搜索只命中可见 text/title；空 q 不扫全文。
- [ ] 现有窄测/编译不被故意破坏。

## 7. 拍板记录（拟默认 · 待你确认）

| 项 | 拟决议 |
|----|--------|
| B 范围 | **仅 API + 仓储 + V014 + receive 门禁**；页面侧栏留给 D |
| 搜索 | **FTS5 主路径** + Committer 增量同步；失败时有界 LIKE 兜底写进草稿 |
| 清空回收站 | 有界批次；缺 conversation 维度的 Outbox 不硬删全表 |
| 鉴权 | 与现有创建会话同级；全站聊天鉴权统一记交接（C 前必须闭合） |
| `api.js` | 允许只加客户端函数，不加 UI |
| 测试文件 | 普通阶段不交付；F 再补 |

## 8. 开工门

**已确认**（2026-09-24）。下一步：`k04-b-draft/` 按核心 6 → 外围顺序草稿；核心审过再入 `wn-server/`。需要改禁区/migration/状态机时先回报。
