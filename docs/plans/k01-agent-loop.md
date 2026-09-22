# 0.2.1 · Agent Loop 施工单（别名 K01）

`status`: **v0.2 · 0.2.1 · 已交付**（A/B/C/D + 审计 P1–P3 收口；次数打满由 0.2.2 continue 路径单测关闭）
`version`: **0.2.1**（施工别名 K01）  
`batch`: 实施清单 **0.2.1**（子批 A / B / C / D）  
`authority`: [产品概览](../product/01-overview.md) · [实施清单](../guide/01-checklist.md) · [05 工作簿](../guide/05-agent-loop.md)  
`schedule`: 全版本顺序只看 [roadmap.md](./roadmap.md)。本文只排 **0.2.1 内部**子批。  
`research`: [research/README.md](../research/README.md) · 插话调度已定 [followup](../research/in-flight-user-message.md)  
`closure`: [03 审计快照](../reviews/03-audit-0.2.1.md) · [04 关闭复核](../reviews/04-reverify-0.2.1.md) · **遗留「决策打满」已由 0.2.2 关闭**；产品下一步 **0.2.3**

给执行者（Codex / luna）的指令。用户保留 `OWNER: USER` 核心循环；Agent 可搭骨架、live 夹具与接线，**不得**擅自补全 `DefaultAgentLoop.run`，除非用户改分工。

**会话调度（已定）：** 同会话 **followup（FIFO）**；不拒收；不做默认 Steer；不用 Jev 调度。C/D 接线须保证双 POST 不双跑；cancel 仅显式路径。

### 0.2.1 收口补丁 · followup 实现（2026-09-22）

对照 [03-audit-0.2.1](../reviews/03-audit-0.2.1.md) P1（F-01…F-05）。

**F-01 执行层（已定 · 方案 A）：**

- 同会话在 **单 JVM** 内用 `conversationId → ReentrantLock(true)`（进程内公平锁）串行整段 `execute` 的 **RECEIVED 与 COMMITTING 恢复**（claim→Loop→freeze/commit 或 recover commit）。
- `receive` 仍不拒收；异键第二 POST 在锁上等待，前序 flight 终态后再 claim，不双跑 Loop。
- **本批不新增** `TURN_QUEUED` 等排队错误码：方案 A 下客户端仍等完整 `reply` / 原有失败码，不把「服务端排队」暴露为 Held。
- 不做跨进程调度、不做队列 UI / 后台 drain（属 0.2.4）；严格「仅按 receive 序且无人 execute 更早 Turn」的 worker 本批不做。
- 会话锁在无等待者时从 map 摘除，避免只增不删；`failAttempt`/`cancelAttempt` 落库失败打 `System.Logger` 警告（尽力而为，对外仍 Held）。

**其余 P1：** F-02 软截止可读；F-03 次数闸门可单测（**0.2.2** 已接 ToolCalls continue 并覆盖打满）；F-04 `CANCELLED`→`Cancelled`；F-05 freeze `RevisionConflict`→`failAttempt`。

### 0.2.1 收口补丁 · P2/P3（2026-09-22）

对照审计 F-06…F-13（不改写审计旧稿）：TimeoutModelPort 共享池 + HTTP 尊重 deadline；用户文案 `ErrorLogFields.redact`；`SafeErrorLog` 接入适配器；`DecisionPort`+`Noop`（未挂 Loop 构造器）；损坏 `wannian.json` 拒绝覆盖；适配器 `ErrorCodes` 字面量收口；`AgentTrace` 单步字段；空 `ToolCalls`→`INVALID_MODEL_OUTPUT`。
---

## 测试策略（已定）

**0.2.1 Loop 行为验收禁止 Fake / Scripted / 内存假模型。** 必须：

- `wannian.model.mode=live`
- 管理面已启用的供应商 + 模型（与 `/chat/`、`probe` 同一适配器）
- 本机可读 API Key；测试允许出站 HTTPS
- 断言结构与不变量（Outcome 类型、非空正文、decide 上界、trace 无密钥、不写库），**不**断言固定逐字回复

`FakeModelAdapter` 可留在仓库供非 Loop 测，**不得**当 0.2.1 通过证据。缺密钥/外网：标明未就绪，禁止静默退 fake。

---

## 0. 事实 / 推断 / 缺省

### 已验证

- v0.1 已封：H1–H4、`/chat/` 直接回答。
- `ModelPort` / `ModelOutcome` 已在 kernel；live 装配已在 app。
- `TurnDialogue`：认领 → 单次 `decide` → freeze → commit；**不是** Agent Loop；工具 → `TOOL_CALLS_UNSUPPORTED`。
- kernel **尚无** `AgentLoop` 包。
- 部分 HTTP 测仍 `mode=fake`；0.2.1 新测不得沿用。

### 静态推断

- 真实模型若返回 `ToolCalls`：0.2.1 受控失败/占位、不写库；真工具属 **0.2.2**。
- **0.2.1-C** 用 `TurnEngine → AgentLoop` 替换直答段，保留 R01–R05 语义。
- 空白/预算打满：0.2.1 难用真模型硬撞；**0.2.2** 用假模型控循环覆盖打满路径即可（仍禁用 Fake 冒充 0.2.1 直接回答 live）。

### 缺省（开工前可改）

| 项 | 缺省 |
|----|------|
| 验收模型 | live + 外网 |
| 供应商/模型 | 本批：管理页已启用绑定；后续改为预设供应商（§11） |
| A / B 顺序 | 先 B 骨架与练习一，A 可并行 |
| 直答退役 | C 通过后再删或委托 |
| maxModelDecisions | 3 |
| 软/硬预算 | 15s / 30s |

---

## 1. 目标与非目标

### 目标

1. `AgentLoop.run(AgentInput, AgentBudget) → AgentOutcome`
2. live 跑通最终回答；工具/预算/取消占位；工具路径按一等公民设计
3. TurnEngine：认领成功后才调 Loop → 冻结计划 → 提交；R01–R05
4. 全进程唯一错误 code（0.2.1-A）
5. live 装配与对外 `execute`（0.2.1-D）
6. 契约预留（§9）：`TurnSource`、`toolProfileId`、`DecisionPort` Noop 等——**不实现**世界线

### 非目标

- ToolRuntime / operationId 存储 → **0.2.2**
- WorldAgent、多节点、Jev 实现、游戏桥 → [roadmap v0.3](./roadmap.md)
- manage `probe` 当会话入口；B 完成前挂 Loop 到会话 HTTP
- Loop 内 JDBC / Controller / 厂商 SDK
- 改原始 Message 做 prompt 裁剪
- Memory / SSE 完整实现 → **0.2.3** / **0.2.4**
- `/chat/` 消息回读、刷新/重开自动恢复历史、接入最近会话 → **0.2.4**（见清单该节；本批不改 chat 恢复 UX）
- Fake 验收；解冻 legacy/han-agent
- 默认 Steer；用 Jev/DecisionPort 裁决排队 vs 打断；生成中 busy 拒收
- 预设供应商与按目录自动读取上下文窗口 → **后续**（见 §11）；本批仍用管理页启用的 openai-compatible 绑定
---

## 2. 允许改动与类型

| 区域 | 允许 | 禁止 |
|------|------|------|
| `kernel/.../agent/`（新建） | Loop / Input / Outcome / Budget / Trace / DefaultAgentLoop（OWNER: USER） | Repository、Spring、SDK |
| `kernel/.../error/`（A） | 稳定 code 登记 | 第二套 reason 表 |
| `kernel/.../turn/` | TurnEngine 编排（C） | Loop 内 commit |
| `app/...` | live 装配、`execute`（D）、live 测夹具 | 页面调厂商；Loop 验收接 Fake |
| `TurnDialogue` | **仅 C/D** 委托或删除 | B 阶段大改接线 |
| 清单 | 勾选本批 | 改写 H3 原文 |

```text
AgentLoop.run(AgentInput, AgentBudget) → AgentOutcome

AgentOutcome:
  FinalResponse | BackgroundAccepted(占位) | ControlledFailure | Cancelled

AgentInput:
  turnId, turnSource(= USER；枚举预留 WORLD…)
  conversationExcerpt, memoryContext?, relationshipSnapshot?,
  userMessage, toolDescriptors?, toolProfileId?, systemInstructions,
  worldContext?（0.2.1 恒空）

AgentBudget: maxModelDecisions=3, soft/hard deadline, cancelToken
  可写真源：数据目录 wannian.json 的 agentBudget
  后续管理页可改项追加在同一 JSON，不另开 properties
  application.yml / 环境变量只作首次种子

DecisionPort: 接口 + NoopDecisionPort（默认不分享）；无 Jev 编译依赖
```

`DefaultAgentLoop` 构造：`ModelPort`；可选 `ToolRuntime`（0.2.1 可无）。不构造依赖 DecisionPort/WorldAgent。

---

## 3. 0.2.1 内部顺序

```text
B0  类型 + DefaultAgentLoop 骨架 + live 夹具 + 预留字段 + DecisionPort Noop
 ↓
B1  用户实现：live FinalAnswer → FinalResponse
 ↓
B2  ToolCalls / 预算 / 取消占位
 ↓
A   错误 code 与日志（可与 B 交错）
 ↓
C   TurnEngine（仅 USER）→ Loop → 冻结 → 提交；R01–R05
 ↓
D   live 装配 / execute；退役直答双路径
```

**硬约束：** B 未过 live 直接回答前不挂会话 HTTP。C 未过 R01–R05 不宣称 Turn 完成。预留字段不得变成半套世界线。

其后批次见 [roadmap.md](./roadmap.md)（0.2.2…），不在本文件展开。

---

## 4. 分步

### B0 · 骨架（Agent）

1. 新建 `com.wannian.server.kernel.agent`（含 `TurnSource`、`toolProfileId`、`worldContext`）。
2. `DefaultAgentLoop`：`OWNER: USER` + `UnsupportedOperationException`。
3. `DecisionPort` + `NoopDecisionPort`（无 TypeSafe/Jev）。
4. Live 夹具；缺 Key 标明未跑。
5. 依赖门禁：kernel 无 Spring/JDBC/SDK/Jev。

交付：可编译；有预留与 Noop；无 WORLD ingress；无会话 HTTP 接线。

### B1 · 练习一（OWNER: USER）

按 [05 §7](../guide/05-agent-loop.md)，验收改 live：

- 简单提示 → `FinalResponse`，正文非 blank；通常 1 次 `decide`
- 不断言固定「你好」
- blank → `ControlledFailure`
- Loop 不访问 DB；trace 无 Key
- Refusal/Failure 用真实响应验证

Agent：红测与夹具；**不**填 `run`。

```text
mvn -pl wn-server/app -am test -Dtest=*AgentLoop*Live*
```

### B2 · 占位分支

| 场景 | 期望 |
|------|------|
| 真实 `ToolCalls` | 不调 ToolRuntime；受控失败；不写库 |
| 决策将满 | 第 4 次 decide 前停 → `BUDGET_EXHAUSTED` |
| cancel 在 decide 前 | `Cancelled`，无出站 |
| 真实 Failure | 映射 ControlledFailure |

完整工具语义 → **0.2.2**。

### A · 错误码

- 单一登记处；映射历史 `reasonCode`
- app 边界翻译 SDK/JDBC/HTTP → code
- 日志：code、操作、关联 ID、耗时、脱敏原因；无 key/SQL/堆栈

### C · TurnEngine

```text
claim → RUNNING
→ Assembler（原文与 prompt 分离）
→ AgentLoop.run（live）
→ Outcome → 草稿 / ControlledFailure
→ freezeCommit → commit
```

必测（live）：同 `clientRequestId` 不增加模型调用；错误 owner 不写正式结果；COMMITTING 恢复不重跑 Loop；同会话串行。对照 [07 R01–R05](../guide/07-testing.md)。

### D · live / execute

- 装配 `DefaultAgentLoop` + live `ModelPort`
- 对外入口走 TurnEngine，不走 probe
- `/chat/api.js` 仍只 `createConversation` / `sendTurn`
- 删除或降级 `TurnDialogue` 直答（**已删** `TurnDialogue`；HTTP 只走 TurnEngine）
- 相关回归改为 live，或拆「无模型 / live」两类

---

## 5. 异常与禁止修法

| 情况 | 行为 |
|------|------|
| 超时 | 预算内有限重试或 ControlledFailure |
| 限流 | `retryable=true`；不紧密打满 |
| 取消 vs beginCommit | 持久化 CAS；合法 COMMITTING 不被普通取消打回 |
| 编程缺陷 | 仍抛异常 |

禁止：Loop 内直接 commit；固定节点名当 `executionId`；prompt 写回 Message；B 阶段挂 HTTP「先聊工具」；粘贴 legacy 主循环；扩做 Memory/SSE；**Fake 当通过证据**；交付日志带 Key 或全文敏感回复。

---

## 6. 验收断言

### B

```text
[x] live：简单输入 → FinalResponse，非 blank；通常 1 次 decide（DefaultAgentLoopLiveTest）
[x] mode=live 且出站真实发生（DeepSeek openai-compatible；需 DEEPSEEK_API_KEY）
[x] 未用 Fake/Scripted 作 FinalResponse 通过证据
[ ] blank/Refusal/Failure → ControlledFailure（难触发；**不挡收口**，见清单说明）
[x] Loop 无 SQL/SDK/Controller import
[x] ToolCalls（0.2.1 当时）：不执行、受控 TOOLS_NOT_ENABLED → **0.2.2 已接真实工具 continue**
[x] maxModelDecisions=3 打满：0.2.2 `DefaultAgentLoopToolContinueTest#alwaysToolCallsExhaustsBudget`（不要求真模型硬撞 3 次）
[x] cancel 在 decide 前 → Cancelled 且无出站
[x] Failure(CANCELLED) → AgentOutcome.Cancelled（非 ControlledFailure）
[x] softDeadline：首次 decide 前仍可开；后续 decide 前过 soft → BUDGET_EXHAUSTED（AgentBudgetGateTest）
[x] maxModelDecisions 闸门可单测（AgentBudgetGate；打满路径随 0.2.2 continue 关闭）
```
### A

```text
[x] 仅一套 code 登记；历史 reasonCode 有映射（ErrorCodes；ManageReason 别名）
[x] 日志样例无 key / SQL / 堆栈（ErrorLogFieldsTest）
```

### C

```text
[x] R01–R05 复验（命令见下；编排补测 TurnEngineOrchestrationTest / TurnEngineLiveCTest）
[x] 重放不增加模型计数；错误 owner / STALE_ATTEMPT 不写助手消息
[x] COMMITTING 恢复不调 Loop；原文与裁剪分离（Assembler + TurnEngine）
[x] 同会话异键并发 execute 不双跑 Loop（会话公平锁 · 方案 A；TurnEngineOrchestrationTest）
[x] freeze RevisionConflict → failAttempt，status ≠ RUNNING
[x] Loop Cancelled → DB CANCELLED
```

复验命令（本机，退出码 0）：

```text
mvn -pl app -am test -Dtest=TurnEngineOrchestrationTest,TurnEngineLiveCTest,ReceiveTurnIdempotencyTest,RecoverableCommitPlanTest,TurnTransitionPersistenceTest,TurnCommitterAtomicityTest,ContextAssemblerTest,TurnTransitionTest,AgentBudgetGateTest,DefaultAgentLoopBudgetTest -Dsurefire.failIfNoSpecifiedTests=false
```
### D

```text
[x] 已启用：HTTP 有 reply；路径经 TurnEngine+Loop；live（TurnEngineHttpLiveDTest）
[x] 未启用：RECEIVED + reason；页面不装已答（无 reply）
[x] probe 仍非会话入口（不建 conversation/turn；/chat 不调 probe）
```

命令：

```text
mvn -pl app -am test -Dtest=TurnEngineHttpLiveDTest,TurnEngineHttpTest,ChatPageTest,ModelProbeHttpTest -Dsurefire.failIfNoSpecifiedTests=false
```

### 与临时代码（§8）

`TurnDialogue` / `TurnDialogueHttpTest` 已不存在；HTTP 只走 `TurnController` → `TurnEngine`。

---

## 7. 交付格式

```text
批次：0.2.1-Bx / A / C / D
实际 diff：
OWNER: USER（路径 + 摘要）：
测试命令与退出码：
mode=live 证明（脱敏）：
出站证明（usage/计数，无 Key）：
关键断言摘录：
未完成 / 指令缺口：
```

审阅按「指令 → 执行 → 纠偏 → 验收」；发现 Loop 走 fake 一律打回。

---

## 8. 与临时代码

| 现状 | 处置 |
|------|------|
| `TurnDialogue` | **已退役**；HTTP 只走 TurnController → TurnEngine |
| `FakeModelAdapter` | 可留；不作 0.2.1 验收 |
| `TurnDialogueHttpTest` fake | 改 live 或拆用例 |
| manage `probe` | 不动；可作连通冒烟 |
| `/chat/` UX | 有 reply 才展示；契约稳定 |

---

## 9. 契约预留（只留缝）

依据 [world-evolution](../research/world-evolution-and-extensions.md)。禁止半套世界线。

| 预留 | 0.2.1 动作 |
|------|----------|
| `TurnSource` | 枚举含预留；C 仅 USER |
| `toolProfileId` | 可空 ≡ chat.default |
| `worldContext` | 可空；0.2.1 不填 |
| `DecisionPort` | 接口 + Noop |
| Memory 来源注释 | WORLD_EVENT 等；可不建表 |
| 多模型注释 | chat/world/decision 可分；仍单启用对话模型 |
| Outbox 注释 | 仅分享世界经历时投用户 |

v0.3 顺序见 [roadmap.md](./roadmap.md)。

---

## 11. 后续：预设供应商与上下文窗口（本批不改代码）

已定方向，实现排在上下文读取接口定下来之后。0.2.1 的管理页仍允许填写 `openai-compatible` 供应商并手选模型。

参照：

- **OpenClaw**：模型写成已知 `provider/model`；provider 来自内置目录（另可接兼容端点），上下文按配置块分配，不要求用户为每条模型手填窗口。
- **Hermes**：走已知后端配置，模型从该后端目录选择，不把「自建任意协议」当默认路径。

wannian 后续收成：

1. 供应商由预设列表提供（协议仍可同为 OpenAI 兼容，但 baseUrl、目录字段由预设决定）。用户选择预设中的供应商与其目录中的模型，不手填协议或上下文长度。
2. 先有一份默认上下文窗口（放在数据目录 `wannian.json`，与 `agentBudget` 同文件、不同键）。
3. 导入或刷新目录时自动读取窗口。标准 `GET /models` 只有 `id`；各家额外字段不统一（如 `context_length`、`max_model_len`）。读哪个键写在供应商预设里。解析不到则保留默认窗口。
4. 上下文预算模块在上述接口确定后再做：窗口减去预留输出与余量，用于裁近讯。现有 `agentBudget`（次数、软硬截止）不并进窗口字段。

本批禁止：为手填窗口加管理项；把预算模块提前做成 token 闸门。

---

## 12. 后续：列出会话（本批不先做）

创建会话已有：`POST /api/conversations` → `ConversationStore.create`。消息近讯已有：`listRecentMessages`（某一个会话内的消息，不是会话清单）。

尚无列出会话。后续补：

1. `ConversationStore.list()`：按更新时间返回会话摘要（id、标题、状态），不含消息正文。
2. `GET /api/conversations` 映射该结果。聊天页用它显示会话列表；点进某一条后再用 `listRecentMessages` 取该会话近讯。

不把会话清单塞进 `ContextAssembler` 或 `AgentLoop`。排在 `TurnEngine` 编排之后。

---

## 10. 开工

1. 通读 [05](../guide/05-agent-loop.md) §1–§7（Fake 练习按本计划改 live）。
2. 本机已启用模型 + Key + 外网。
3. 授权 **B0** 或 **B0+B1**。
4. 未授权不 commit / push / 部署。

下一句指令示例：「按计划执行 0.2.1-B0」或「B0 骨架 + 我来写 B1」。
