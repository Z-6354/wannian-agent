# 0.2.1 · Agent Loop 施工单（别名 K01）

`status`: **v0.2 · 0.2.1 · 未开工**  
`version`: **0.2.1**（施工别名 K01）  
`batch`: 实施清单 **0.2.1**（子批 A / B / C / D）  
`authority`: [产品概览](../product/01-overview.md) · [实施清单](../guide/01-checklist.md) · [05 工作簿](../guide/05-agent-loop.md)  
`schedule`: 全版本顺序只看 [roadmap.md](./roadmap.md)。本文只排 **0.2.1 内部**子批。  
`research`: [research/README.md](../research/README.md) · 插话调度已定 [followup](../research/in-flight-user-message.md)

给执行者（Codex / luna）的指令。用户保留 `OWNER: USER` 核心循环；Agent 可搭骨架、live 夹具与接线，**不得**擅自补全 `DefaultAgentLoop.run`，除非用户改分工。

**会话调度（已定）：** 同会话 **followup（FIFO）**；不拒收；不做默认 Steer；不用 Jev 调度。C/D 接线须保证双 POST 不双跑；cancel 仅显式路径。
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
- 空白/预算打满优先用真实失败或取消触发；难触发则记待补，不降「禁 fake」。

### 缺省（开工前可改）

| 项 | 缺省 |
|----|------|
| 验收模型 | live + 外网 |
| 供应商/模型 | 本机 manage 已启用绑定 |
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
- Fake 验收；解冻 legacy/han-agent
- 默认 Steer；用 Jev/DecisionPort 裁决排队 vs 打断；生成中 busy 拒收
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
- 删除或降级 `TurnDialogue` 直答
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
[ ] live：简单输入 → FinalResponse，非 blank；无多余 decide
[ ] mode=live 且出站真实发生
[ ] 未用 Fake/Scripted 作通过证据
[ ] blank/Refusal/Failure → ControlledFailure（难触发则待补）
[ ] Loop 无 SQL/SDK/Controller import
[ ] ToolCalls：不执行、不写库
[ ] maxModelDecisions=3（难触发则待补，仍禁 fake）
[ ] cancel 在 decide 前 → Cancelled 且无出站
```

### A

```text
[ ] 仅一套 code 登记；历史 reasonCode 有映射
[ ] 日志样例无 key / SQL / 堆栈
```

### C

```text
[ ] R01–R05 复验（命令与输出贴审阅）
[ ] 重放不增加模型计数；错误 owner 三表不变
[ ] COMMITTING 恢复不调 Loop；原文与裁剪分离
```

### D

```text
[ ] 已启用：HTTP 有 reply；路径经 TurnEngine+Loop；live
[ ] 未启用：RECEIVED + reason；页面不装已答
[ ] probe 仍非会话入口
```

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
| `TurnDialogue` | B 保留；C 委托；D 去双路径 |
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

## 10. 开工

1. 通读 [05](../guide/05-agent-loop.md) §1–§7（Fake 练习按本计划改 live）。
2. 本机已启用模型 + Key + 外网。
3. 授权 **B0** 或 **B0+B1**。
4. 未授权不 commit / push / 部署。

下一句指令示例：「按计划执行 0.2.1-B0」或「B0 骨架 + 我来写 B1」。
