# 01 · 实施清单

`status`: **v0.1 已封版** — 2026-09-20 重置。v0.1 = 当前已交付（骨架、持久化、管理页、直接回答）。下一步是 **v0.2 单核 harness + 单核节点**（**0.2.1** 起）。世界树与多核是 **v0.3**，本清单不提前开工。

`plan-revised`: **2026-09-22** — 版本三档见 [产品概览 §3](../product/01-overview.md)。v0.2 正式小版本为 **0.2.1–0.2.7**（`K01`–`K07` 为施工别名）。`/chat/` 直接回答留在 v0.1，不再算成 0.2.1。**0.2.4** = 行为账本（A）→ Outbox/SSE + `/chat/` 历史恢复（B）；立项 [k04-behavior-journal.md](../plans/k04-behavior-journal.md)。旧号对照仍有效。审查原文不改写。

## 版本

| 档 | 状态 | 含 | 不含 |
|----|------|----|------|
| **v0.1** | 已交付 | H1–H4、`/chat/` 直接回答 | Agent Loop、工具、记忆策略、Outbox、多节点 |
| **v0.2** | 进行中 | 单核 harness（**0.2.1–0.2.3 已交付**；下一 **0.2.4**）、一个烟火节点 | 世界树、第二个节点、Guardian |
| **v0.3** | 未开工 | 世界树、多核节点 | 宿主主备、wn-agent |

## 使用规则

- 每次只实施一个 **0.2.x** 中的一个小批次。
- 用户编写 `OWNER: USER`，Codex 不擅自补全。
- 每批完成后提交实际差异、命令和测试证据供审阅。
- 未明确授权时不 commit、push、部署或接触生产凭据。
- 超出计划的协议改变、扩大模块或提前实现 v0.3 / 更后，先补充方案再实施。
- 每阶段开工先读自己的“防复发提示”，验收同时检查拒绝/冲突路径的数据库不变断言，不能仅凭正常路径或测试总数勾选完成。
- 除 **0.2.7** 明确区分的外部资源验收外，下列批次均为 **A · 本机可验证**；`OWNER: USER` 是实现分工，不免除 Agent 准备骨架、解释与验证证据的责任。
- 旧文档若仍写旧号或 `K0x`，以本文件对照表换算；进度只认本文件 **0.2.x** 编号。

## 编号对照

| 旧号 / 别名 | 现行正式号 | 说明 |
|---|---|---|
| K01（工程骨架） | 历史 H1 | 已交付 |
| K02（持久化） | 历史 H2 | 已交付；补修证据在 H3 |
| K03-P | 历史 H3 | 已放行，见 [02 放行](../reviews/02-release-h3.md) |
| K03-W / K03-B / K03-X | 历史 H4 | 管理页、适配器、索引清洗已交付 |
| K03-A / C / D / E；施工别名 K01 | **0.2.1** 子批 A / B / C / D | 错误码、Loop、Turn 接线、execute |
| K04；施工别名 K02 | **0.2.2** | ToolRuntime |
| K05；施工别名 K03 | **0.2.3** | Memory / Relationship |
| K06；施工别名 K04 | **0.2.4** | 行为账本 → Outbox / SSE / 内嵌对话页 |
| K07；施工别名 K05 | **0.2.5** | Task / BackgroundTask |
| K08；施工别名 K06 | **0.2.6** | 生命周期探针 |
| K09；施工别名 K07 | **0.2.7** | 故障、恢复与资源 |

审查稿、测试工作簿里的旧号是当时写法；新开工一律用 **0.2.x**。施工单文件名可仍用 `k01-…`，文首须写正式号。

---

## 历史交付

下列工作已关闭。细节以代码、[02 放行](../reviews/02-release-h3.md) 与 [08 批次记录](history/08-manage-adapter.md) 为准。不要按历史目标重做。

### H1 · 工程骨架与依赖门禁（旧 K01）

`api` / `kernel` / `app` 三模块；`/internal/live`；依赖门禁与负例（含 P-E / T6）。

### H2 · SQLite、Conversation、Message、Turn（旧 K02）

Flyway V001–V004；`receive` 幂等；Turn 状态机；`commit` 同事务写 Message/Turn/Outbox；一致性备份；HTTP 建会话与接收回合。不做完成回合的模型调用。

### H3 · 接入模型前的补修与协议收口（旧 K03-P）

T1—T7 关闭；T8 重复索引延期到相关 migration 或最迟现行 **0.2.7**。约束仍生效：全局 `clientRequestId`；独立 `executionId`；COMMITTING 冻结计划；完成事件与序号由 committer 分配。R03 进程强杀、R10 整库堆峰值属 **0.2.7**。

### H4 · 管理页、模型适配器与索引清洗（旧 K03-W / B / X）

- 管理页：V005–V006、`/api/manage/model`、本机免口令、`/manage/` 静态壳。
- 适配器：`VendorAdapter`、`ModelPort.decide`、TTL 目录缓存、`POST /probe`。探测不是会话入口。
- 清洗：索引进度与 `.gitignore` 的 `target/`；未删审查原文、研究存档或仍有独立断言的测试。

---

## 当前 · v0.1 内嵌对话页

`status`: **已完成，计入 v0.1** — `/chat/` 与管理壳共用导航；已启用模型时发送会在同一次请求里完成直接回答；未启用时只收下回合并说明原因。这不是 v0.2 harness。

- 页面在 `/chat/`，不嵌进管理面板。管理侧栏只链出去。关掉管理页后这个入口仍在。
- 页面脚本不写 URL、不 `fetch`。唯一 HTTP 出口是 `/chat/api.js` 的 `createConversation` / `sendTurn`。
- 这两个函数打 `POST /api/conversations` 和 `POST /api/conversations/{id}/turns`。已启用模型时响应带 `reply`，回合进入 COMPLETED。同一 `clientRequestId` 重放已完成回合只回放已保存回复，不再调用模型。
- 未启用模型时回合停在 RECEIVED，`detail` / `reasonCode` 说明原因。页面不得在没有 `reply` 时装作已经回答。
- 不做 SSE、Outbox 补发、消息回读，也不执行工具。刷新后本页不恢复历史。
- **延期到 0.2.4**：消息回读、刷新/重开后自动恢复 transcript、关闭服务再开后接入最近会话（见下文 **0.2.4**）。v0.1 / 0.2.1–0.2.3 不得提前做完整恢复 UX。
- Loop 完成后只改 `api.js`（以及届时的服务端对话接口），不把厂商调用写进页面。

---

## v0.2 · 0.2.1 起

下列 **0.2.1–0.2.7** 全部属于 **v0.2 单核 harness + 单核节点**。不要在 v0.1 上补做。  
**0.2.1 / 0.2.2 / 0.2.3 已交付**；下一默认工作 **0.2.4**。

## 0.2.1 · 错误码、Agent Loop 与 Turn 接线（别名 K01）

`status`: **v0.2 · 0.2.1 已交付**（A/B/C/D + 审计 P1–P3 收口）  
**原「决策打满 live」遗留已关闭：** 0.2.1 时 Loop 不对 ToolCalls `continue`，真模型几乎打不满 3 次 decide。**0.2.2 已接 continue**；窄测 `DefaultAgentLoopToolContinueTest#alwaysToolCallsExhaustsBudget` 覆盖「反复 ToolCalls → BUDGET_EXHAUSTED」。不要求再用真模型硬撞满 3 次。blank/Refusal 等难控路径有单测即可，**不挡收口**。
`closure`: [施工单](../plans/k01-agent-loop.md) · [03 审计快照](../reviews/03-audit-0.2.1.md) · [04 关闭复核](../reviews/04-reverify-0.2.1.md)

### 目标

使用已启用模型完成有预算的决策循环，并把结果经 Turn 提交。同时完成**全进程唯一**的错误 code 与日志约定；**0.2.2** 及以后引用这套规范。

### 子批

| 子批 | 旧称 | 状态 | 交付 |
|---|---|---|---|
| 0.2.1-A | K03-A / K01-A | **已交付** | 全进程错误 code 与日志约定；收编历史 H2 的 `reasonCode` |
| 0.2.1-B | K03-C / K01-B | **已交付**（live：`DefaultAgentLoopLiveTest`；次数打满见 0.2.2 `DefaultAgentLoopToolContinueTest`） | Loop 类型与直接回答 |
| 0.2.1-C | K03-D / K01-C | **已交付**（编排见 `TurnEngineOrchestrationTest`；live 见 `TurnEngineLiveCTest`；R01–R05 复验命令见施工单） | TurnEngine：认领 → Loop → 冻结计划 → 提交；R01—R05 复验 |
| 0.2.1-D | K03-E / K01-D | **已交付**（`TurnEngineHttpLiveDTest`；`TurnDialogue` 已退役） | live 装配与对外 `execute` 入口 |

### 防复发提示（T1/T2/T3/T5/T7）

TurnEngine 管认领、上下文与提交，Loop 只返回 Outcome。认领成功后才调用模型；重复请求、旧 owner 的迟到结果不能触发第二次业务。原始 Message 不因 prompt 裁剪改变。沿用 H3 的持久化计划恢复。管理页不直连厂商、不分配 Turn 序号。

### 异常系统与日志系统

分类起点见 [`28`](04-kernel-reference.md) 第 14 节；预期业务失败用封闭结果（见 [`34`](03-architecture.md) 第 11 节）。

- 稳定 `code` 只登记一处：`com.wannian.server.kernel.error.ErrorCodes`（0.2.1-A 已交付）。
- 厂商 SDK、JDBC、HTTP 异常在 app 边界译成上述 code，不得原样进入 kernel。
- 编程缺陷与进程级不可恢复故障仍走异常（`InternalDefectException`）。
- 日志只记稳定 code、操作类别、关联 ID、耗时与脱敏原因（`ErrorLogFields` + app `SafeErrorLog`）；不含密钥、SQL、堆栈或原始敏感正文。边界适配器（Timeout / OpenAI 兼容）已接线 `SafeErrorLog`；非要求全仓每一处 Logger 立刻统一。
- 历史 H2 的 `reasonCode` 与管理面 `ManageReason` 已收进这一套（`ManageReason` 仅为别名，禁止再增字面量表）。

```text
[x] 全进程只有一套错误 code 与一套日志约定
[x] 历史 reasonCode 已纳入，没有第二套
[x] 日志约定类型可脱敏；边界路径 SafeErrorLog 已接线；样例不含 API key、SQL 或堆栈
[x] kernel 不依赖具体日志实现或厂商异常类型
```

### Codex 可生成

- **A：** 错误模型与日志约定的类型、登记处和最小实现；
- **B—D：** `AgentInput/Outcome/Budget/Trace`；`DefaultAgentLoop` 带 `OWNER: USER` TODO；TurnEngine 与提交恢复测试；live/`execute`。

### 用户实现（OWNER: USER）

- `DefaultAgentLoop.run`（B）；
- ContextAssembler 中上下文取舍策略；
- 模型拒绝、空回答和预算耗尽的产品行为；
- 用户可见的基础错误文案。

### 验收

```text
[x] 直接回答测试（0.2.1-B；`DefaultAgentLoopLiveTest` live）
[x] 工具分支占位测试（非空 ToolCalls→TOOLS_NOT_ENABLED；空→INVALID_MODEL_OUTPUT）——0.2.2 起已改为真执行路径
[x] 最大模型决策打满 → `BUDGET_DECISIONS_EXHAUSTED`（闸门：`AgentBudgetGateTest`；continue：`DefaultAgentLoopToolContinueTest`；软/硬为独立 code）
[x] 软/硬截止可配置且生效（软截止后不再开新 decide；硬截止挡 decide 前；现行种子默认软 60s / 硬 100s，决策上限 10）
[x] 系统工具 `remember_fact` / `update_relationship` / `search_memory` 不计入决策次数（`countsTowardDecisionBudget=false`）
[x] 全进程只有一套错误 code 与一套日志约定；适配器引用 ErrorCodes
[x] 历史 reasonCode 已纳入，没有第二套
[x] 超时/限流/格式错误使用该套 code
[x] 日志不含 API key、SQL 或堆栈；边界 SafeErrorLog 已接线
[x] kernel 不依赖具体日志实现或厂商异常类型
[x] Loop 不 import SDK/SQL/Controller
[x] 同键重试不增加模型调用；错误 owner 的迟到 Outcome 不写正式事实
[x] 模型完成但提交响应丢失时回放已保存结果，不重跑模型
[x] 同会话 followup 串行（方案 A 公平锁，异键不双跑 Loop）；同键幂等见 ReceiveTurnIdempotency
[x] Failure(CANCELLED)→Cancelled→DB CANCELLED（产品 Stop HTTP 未接，后置）
[x] 原文保存与 prompt 裁剪分离；COMMITTING 恢复不依赖进程内 Outcome；freeze RevisionConflict→FAILED
```

### 禁止

- 按 Controller、持久化、模型、工具各写一套错误码或日志格式；
- 把 manage `probe` 当成 Turn 业务路径，或在 B 之前把 Loop 接到 HTTP 会话接口；
- 把管理页扩大成 **0.2.4** 对话页 / SSE，或建成独立 wn-manage 工程。

## 0.2.2 · ToolRuntime 与只读工具（别名 K02）

`status`: **已交付**（2026-09-22）— 阶段 1–4 代码 + 窄测 + live（`list_tools` 真工具往返）；施工单 [k02-tools.md](../plans/k02-tools.md)。

### 防复发提示（T1/T2 的同类问题）

`operationId` 须绑定工具名、规范化参数摘要与来源 Turn/Run。执行权用当前尝试身份。外部调用在短事务之外，超时不是“肯定没执行”。

### 目标

深 Module `ToolRuntime`，提供 `list_tools`、`current_time`、`calculate`，可选受限 `http_read`；Windows 另提供 `powershell_resolve_5` / `_7`（启动探测 family 标签求交，模型只见一个；见 [k02-tool-impl-binding](../plans/k02-tool-impl-binding.md)）。

### Codex 可生成

- descriptor/schema/request/result；
- `ToolRuntime` Interface 与封闭 outcome；
- 内部 catalog、validator、policy、budget；
- ToolOperation 存储；
- Local/Fake Adapter 骨架与幂等测试。

### 用户实现

- 工具对模型可见的描述；
- Loop 只接入 `ToolRuntime.execute`；
- 工具错误如何形成 observation（使用 **0.2.1** 的错误 code）。

### 验收

```text
[x] 不存在工具不会执行
[x] 非法参数不会进入 Adapter
[x] 每次执行有 operationId
[x] 结果受大小限制；超时有限
[x] 相同 operationId 参数冲突被拒绝
[x] Agent Loop 不直接依赖 Validator、Policy、OperationStore 或具体 Adapter
[x] 无 Shell、任意文件写入、桌面工具（PS 本批仅只读解析）
[x] 改工具名/参数/来源时冲突，Adapter 调用次数不增加
[x] 响应丢失可查原结果；UNKNOWN 不盲重试
[x] ToolCalls continue + 次数打满 → `BUDGET_DECISIONS_EXHAUSTED`；系统工具回合可不计次数
[x] 角色×模式×主机求交；管理页三态与本机预览
[x] live：真模型可调用 list_tools 并回灌（端口 8080 实机）
```

证据（2026-09-22）：`ToolRuntimePhase1Test`、`ToolVisibilityPhase2Test`、`DefaultAgentLoopToolContinueTest`、`ContextAssemblerToolVisibilityTest`、`ToolUsePolicyTest`、`ToolSettingsTest`、`ToolManageHttpTest`、`OpenAiCompatibleModelAdapterTest`；装配见 `ToolRuntimeConfig` / `TurnEngineConfig`；live：`list_tools` 对话往返。

**明确不在本批：** 会话历史恢复（→0.2.4-B）；任意 PS 执行（更后）。可选未做见施工单 §12。本回合工具调用投影已在 0.2.3 收口附带。

## 0.2.3 · Memory 与 Relationship（别名 K03）

`status`: **已交付**（2026-09-24）；施工单 [k03-memory.md](../plans/k03-memory.md)；设计 [memory-system-0.2.3.md](../research/memory-system-0.2.3.md) §4 + §4.1。

交付范围：热路径 `remember_fact` / `update_relationship` → Shape → Policy（仅密钥拒）→ Freeze 同事务；冷路径 Review Job；衰减召回 + Assembler 注入；弱 B 扫墓；HTTP GET/correct/forget；`search_memory`；运行日志 JSONL + `turn_step`（L）；系统工具每工具上限 5 / 普通决策 10；`/chat/` 本回合工具调用投影。

自动化：kernel + app 全量通过（含 V008–V013）。真人路径：预算与枚举可见性修复后，可记可搜可答。

### 防复发提示（T1/T2/T7）

核验 `sourceTurnId`；Approved* 不可变；冲突后重新评估。硬门槛本批仅密钥；**禁止**内容启发式/正则抽记；tool/Worker/Scanner 不直写库；**禁止**第二次 LLM 只打 importance；弱 B **零 LLM**。

### 验收

```text
[x] 用户已逐层选定（设计文 §4）；k03 按选定组合实施
[x] R1：importance + claim 规范化 + 衰减召回 + 弱 B
[x] 阶段 A→D+/L 实现与代码审；全量自动化通过
[x] 真人/live：直答、记忆写入/召回、关系更新可用（预算 10/60/100；系统工具独立上限）
[x] 运行日志：PROCESS_START/SHUTDOWN、MODEL_CALL、TOOL_CALL（JSONL + turn_step）
[x] 密钥拒、非密钥可落库；缺 importance 拒；Review 不挡 Turn；关系与记忆解耦
[x] 衰减召回与弱 B（单测 + MemoryDecay）；高分长期可召回语义已钉
[x] GET/correct/forget；Assembler 按 score 注入记忆 + rel
```

## 0.2.4 · 统一行为账本 → Outbox、SSE 与内嵌网页（别名 K04）

内部顺序硬约束：**0.2.4-A 账本加厚 → 0.2.4-B 交付/恢复**。A 未勾完不得勾选整批 0.2.4。立项见 [k04-behavior-journal.md](../plans/k04-behavior-journal.md)。

### 防复发提示（T1/T3/T5，审查 §6.5）

复用 H3 的必需完成事件与事务内序号。最小单用户鉴权覆盖 HTTP、SSE、历史补发和 internal；**0.2.6** 再扩展探针测试。  
`/chat/` 历史恢复只读已提交 Message，**不得**在恢复路径上调用模型 / Loop / ToolRuntime。  
账本与 outbox 分工：`turn_step` = 逐步事实；`outbox_event` = 已提交后的可靠交付；禁止用 `log.info` / 仅内存 `AgentTrace` 冒充统一日志。

### 目标

1. **0.2.4-A**：加厚行为账本——`MEMORY_WRITE` 等 kind、与 Outbox 分工文档化（`turn_step` 表与 MODEL/TOOL 写入已由 **0.2.3-L** 落地）。
2. **0.2.4-B**：可靠交付已提交事件；页面断线不重做业务；补齐 **消息回读 + 自动恢复历史 + 接入最近会话**。

### 已验证缺口（写入本批的原因）

- `turn_step` / JSONL 已有 MODEL/TOOL/启停；**尚缺** MEMORY_WRITE 专项与 Outbox 分工收口。
- 服务端：会话与消息已在 SQLite；近讯有 `listRecentMessages`，**无**面向 `/chat/` 的 GET 回读。
- 前端：本回合工具调用可投影；`conversationId` 仍仅 `sessionStorage`；刷新仍不回放历史。
- 关标签页会丢记住的 id；关服务再开不会自动挑最近会话。

### Codex 可生成

- **A：** MEMORY_WRITE 接线；与 Outbox 分工；查询加厚；脱敏与回滚测试。
- **B：** OutboxPublisher；SSE；event cursor；断线重连与鉴权测试。
- 会话只读 API + `/chat/` 加载时拉历史；`localStorage` 记住会话 id。

### 用户实现

- 页面展示文案与顺序；哪些内部事件可进 SSE。
- 「新会话」与「恢复最近会话」的交互确认。

### 范围边界

| 做 | 不做（本批） |
|----|----------------|
| 账本加厚 + Outbox 分工 | 用 ErrorCodes / SafeErrorLog 冒充行为审计 |
| 刷新/重开后自动恢复已提交历史 | 多会话侧栏、跨设备同步 |
| 恢复路径只读已提交 Message | 重做已由 0.2.3-L 交付的 turn_step 窄版 |

### 验收

```text
# 0.2.4-A 行为账本加厚
[ ] MEMORY_WRITE（或等价）可追溯；与 Turn 同事务边界一致
[ ] 与 Outbox 分工文档化；回滚不残留半提交；敏感字段脱敏
[ ] AgentTrace 非唯一真相源（turn_step 已有则复用）

# 0.2.4-B 交付与历史恢复
[ ] 提交后才发送 SSE；lastEventId 补发正确；重连不增加模型/工具调用
[ ] GET 可按 conversationId 回读已提交消息；可查最近 ACTIVE 会话
[ ] 打开 `/chat/` 自动接入记住的或最近会话并渲染历史；恢复路径零次模型/工具调用
[ ] 「新会话」可清空本地记忆；空态不再声称「刷新后本页不回放历史」（或仅在确无可恢复会话时）
```
## 0.2.5 · TaskRuntime、BackgroundTask 与 SubAgentRun（别名 K05）

### 防复发提示（T1/T2/T3/T7）

每次 attempt 独立身份与 lease。旧 attempt 迟到结果不能更新正式任务结果。TaskDraft 仍经 TurnCommitter。

### 目标

长任务不占住对话；失败可追踪、可重试。

### Codex 可生成

- `TaskRuntime`、TaskDraft、状态机；Repository；LocalTaskExecutor；lease/retry/cancel 骨架。

### 用户实现

- BackgroundPolicy；TaskResult 语义；任务完成后的交付表达。

### 验收

```text
[ ] Task 与确认回复同事务；TaskDraft 只经 CommitTurnPlan
[ ] 原 Turn 完成后可继续聊天；重试创建新 Run
[ ] LOST lease 不复活；取消阻止新 Run
[ ] SubAgent 不直接写 Memory/Relationship，不冒充最终回复
[ ] 旧 attempt 不能提交新 attempt 的结果
[ ] 取消与完成竞争有唯一裁决；任一步失败全部回滚
```

## 0.2.6 · 外部生命周期探针（别名 K06）

### 防复发提示（T4，审查 §6.6）

固定绝对数据目录。沿用 **0.2.4** 访问策略。drain 不能强行取消已冻结 COMMITTING，也不能释放 owner 让双执行并存。

### 目标

为未来 Guardian 提供最小稳定协议，不实现 Guardian。

### Codex 可生成

- `/internal/live`、`ready`、`version`、`drain`；probe DTO 与测试。

### 用户实现

- ready 所需依赖；drain 体验与最大等待时间。

### 验收

```text
[ ] live 不把所有依赖当必要条件；ready 检查库与核心装配
[ ] version 返回 build/source/protocol
[ ] drain 后不接新 Turn，等待现有 Turn 至终态或超时
[ ] 无 stable/previous/active 管理；无进程自我替换
[ ] 两 cwd 同绝对数据路径仍开同库
[ ] drain 超时不伪造完成或重置 COMMITTING
```

## 0.2.7 · 故障、恢复与资源验收（别名 K07）

### 防复发提示（T1—T8 的最终交叉验证）

验证已确定的恢复协议，不在此时才补 owner、序号或提交计划。不得把新 JDBC 连接等同于完整进程重启，也不得把普通主机实测等同于 2 核 2 GB。

参与拆分：**0.2.7-A** 本机故障注入与临时库恢复；**0.2.7-B** 仅在需要等价资源限制时由用户或对端回传证据。B 未回传不得勾选资源验收。

### 目标

证明 0.1 在声明故障模型内可恢复，并适配国内 2 核 2 GB 节点。

### Codex 可生成

- FailureInjector；恢复扫描器骨架；资源测试脚本与验收报告模板。

### 用户实现

- 运行并理解每个故障场景；修正不符合预期的核心行为。

### 验收

```text
[ ] 04/07 的故障点已运行；无双 owner；无盲目重复副作用
[ ] 已提交 outbox 可补发；SQLite 备份恢复成功
[ ] 2 核 2 GB 等价环境通过；R01—R12 全部完成
[ ] COMMITTING 前按 step/operation 判定能否重试；进入后只提交冻结计划
[ ] 迁移/备份失败、残缺快照、手工同名目录均已验证
[ ] 重复索引已以查询计划处理或明确保留（T8），不重写已发布 migration
```

## 最终交付证据

```text
实现提交/工作树状态：
参与档 A / B 与 B 侧待回传证据：
审查 T1—T8 对应关闭状态 / 修复验收报告：
模块与文件清单：
OWNER: USER 完成位置：
全部测试命令与退出码：
错误输入 / 冲突 / 并发 / 取消 / 重试的关键断言与实际输出：
故障注入报告：
SQLite 备份恢复报告：
2 核 2 GB 资源报告：
已知限制：
明确延期：v0.3 世界树与多核；更后的 Guardian / wn-agent。
```

**0.2.1–0.2.7** 全部通过，才能把 **v0.2** 标成完成。v0.1 已按当前进度封版，不把 harness 欠账算回去。
