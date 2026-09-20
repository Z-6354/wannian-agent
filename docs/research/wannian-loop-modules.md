# wannian · 循环要做哪些模块

`status`: **draft** — 2026-09-20（同日修订：工具画像 / 成长核心 / Jev DLC / 世界演进预留）  
`premise`: Agent = **一个循环** + **其他模块**（见 [agent-loop-survey.md](./agent-loop-survey.md)）。本文列本项目模块、**参照哪些 Agent**、以及特殊化。  
`owns`: 模块清单。排期见 [roadmap](../plans/roadmap.md)；K01 见 [k01](../plans/k01-agent-loop.md)。拓扑 / 叙事见多核心与世界演进稿。

**产品特殊化轴（贯穿全文）：**

| 轴 | 含义 | 对循环体系的约束 |
|----|------|------------------|
| **二次元个人问答 / 陪伴** | 单用户；烟火为人设与关系主体 | 人设/关系/记忆走 Context；Loop 瘦 |
| **可成长 + 学习记忆** | **核心**，不是装饰 | Memory/Relationship 主路径；世界经历也可沉淀 |
| **工具有占比** | 常 0 工具，但识图 MCP / 定时 / IM / 应用 API / **游戏工具集** 一等公民 | `ToolProfile` 换目录；Loop 只看当前 descriptors |
| **Jev DLC** | System One 决策模型；不加也能跑 | `DecisionPort` + Noop；JevAdapter 可选 |
| **世界树角色** | 只向烟火投事件；烟火可选分享 | 叙事在世界演进稿；K01 只留类型 |
| **多核心 / 宿主主备** | 设备、节点、角色分开；主备是更后的另一层 | 不在本文展开，见多核心拓扑稿 |

开源对照总表见 [world-evolution-and-extensions.md](./world-evolution-and-extensions.md)。Agent 代号：L1–L5、G1–G5 见调研。

---

## 1. 总览

```text
UserChannel / WorldScheduler / GameBridge / …
        │  TurnSource = USER | WORLD | …（WORLD 后置实现）
        ▼
┌─ TurnEngine ───────────────────────────────────────────────────────┐
│  DecisionPort?（Jev DLC 或 Noop）                                   │
│  Companion │ ContextAssembler（人设·关系·记忆·世界摘要）            │
│  ToolCatalog(profileId) → toolDescriptors                          │
│        ▼                                                           │
│  ┌────── AgentLoop（唯一对用户开口的循环）─────┐                   │
│  │  ModelPort ↔ ToolRuntime → AgentOutcome     │                   │
│  └─────────────────────────────────────────────┘                   │
│  Memory/Relationship after │ TurnCommitter │ Outbox（可选分享）    │
└────────────────────────────────────────────────────────────────────┘
```

设备 / 节点 / 角色不画在这张模块图里，见 [multi-node-companion-world.md](./multi-node-companion-world.md)。

| 身份 | 名称 | 一句话 |
|------|------|--------|
| **循环** | `AgentLoop` | 只属于烟火角色 |
| **世界树** | WorldAgent | 只产事件，不对用户说话 |
| **数据面** | 唯一 Persist | 与节点数无关 |
| **壳 / Outbox** | 烟火角色 | USER；世界事件后才可能分享 |
| **DLC** | DecisionPort → Jev | 烟火「说不说」 |

---

## 2. 逐模块：参照 Agent × 本项目特殊化

### 2.1 AgentLoop（唯一循环）· K01-B · `OWNER: USER`

| | |
|--|--|
| **契约** | `run(AgentInput, AgentBudget) → AgentOutcome` |
| **主参照** | **G4** stop_reason while；**G1** NextStep→Outcome 枚举；**L2** 核壳分离（只抄边界） |
| **辅参照** | L5 瘦 while；L1 预算告警/收口语气（摘规则不整搬） |
| **勿参照** | L3 单文件胖循环；G2 checkpointer-in-loop；G5 workspace 进 while |

**二次元 / 个人问答特殊化**

- 多数 Turn **0 次工具**即 FinalResponse，但工具路径是**一等公民**（有一定占比：识图 MCP、定时、日后 IM/API、游戏目录）。
- 空答/拒绝/预算尽的 **safeUserMessage** 可人设化；**code** 机器稳定。
- Loop **不**挑选 ToolProfile、不决定是否主动私信——只消费已注入的 `toolDescriptors` 与消息。

**多核心 / 世界演进特殊化**

- Loop **无** `kernelGenerationId` / 「我是否世界核」分支；世界核不对用户跑此 Loop。
- 换宿主核 = 换制品跑同一契约；主动私信与用户聊共用同一 `AgentLoop`。

---

### 2.1b DecisionPort（Jev DLC）· 契约 K01 预留 · 实现后置

| | |
|--|--|
| **契约** | `evaluate(state, questions) → typed answers + confidence` |
| **主参照** | TypeSafe Jev；LangChain harness 文；Yuralume 三门主动；本仓 `typesafe-probe` |
| **默认** | `NoopDecisionPort` / 轻量启发式 —— **无 Key 系统仍完整** |
| **DLC** | `JevDecisionAdapter`：烟火「说不说」、工具风险；失败降级 Noop（默认不分享） |

**特殊化：** 不生成用户正文。**不是**世界用来决定吵用户；分享冲动只在烟火侧。

---

### 2.1c WorldAgent + WorldClock（世界演进）· 主 Agent 后 · K01 只留类型

| | |
|--|--|
| **主参照** | Yuralume 日程；Pulse/Tanya（多数心跳沉默）；theNPC director；OpenClaw/Hermes cron |
| **职责** | 排生活事件 → **只投递给烟火**；禁止写用户通道 |
| **双模型** | 世界规划模型 ≠ 烟火对话模型；「说不说」可用 Jev（烟火侧） |

产品叙事见 [world-evolution-and-extensions.md §0](./world-evolution-and-extensions.md)。

### 2.2 AgentBudget · K01-B

| | |
|--|--|
| **契约** | maxModelDecisions（默认 3）、软 15s / 硬 30s、cancelToken |
| **主参照** | G1 `max_turns`；G3 `max_steps`；L1 `AgentBudget` + warn ratio |
| **辅参照** | L3 grace call（可后加「再给一次收口」）；G5 美元预算（**不做**） |

**二次元特殊化：** 陪伴闲聊预算宜紧，避免工具空转拖垮体验；硬超时后的用户可见句要温和、不甩栈。  
**多核心特殊化：** 预算是 **单次 Turn 尝试** 的属性，跟代际无关；切换 SERVING 核不得重置「已冻结 COMMITTING 计划」的语义。

---

### 2.3 AgentInput / AgentOutcome / AgentTrace · K01-B

| | |
|--|--|
| **契约** | 不可变 Input；Outcome：Final / BackgroundAccepted / ControlledFailure / Cancelled；Trace 脱敏 |
| **主参照** | G1 NextStep 形状；G4 stop_reason 分类；L2 / L4 事件字段（step、tool、status） |
| **勿参照** | 返回裸 `String`/`null`（L1 旧习） |

**二次元特殊化**

- Input 显式槽位：`systemInstructions`（人设）、`relationshipSnapshot`、`memoryContext`、`userMessage`、`conversationExcerpt`。
- Trace **不**记私密倾诉全文、不记人设隐私；只记 step 类型与 code。

**多核心特殊化**

- Outcome / 冻结计划必须能在 **另一 Kernel 实例** 上由 Committer 完成提交（进程崩溃换核仍靠 COMMITTING 计划，不靠内存 Outcome）。
- Trace 可带 `executionId`；日后可加 `generationId` 仅作审计，不参与 Loop 分支。

---

### 2.4 ModelPort · 已有（H4）

| | |
|--|--|
| **契约** | `decide → FinalAnswer \| ToolCalls \| ModelRefusal \| Failure` |
| **主参照** | G4 模型只决策不执行；本仓 `OpenAiCompatibleModelAdapter` |
| **辅参照** | L2 流式（K04 再接 SSE）；G1 单 turn 调用 |

**二次元特殊化：** 人设与安全规则在 **请求消息** 侧注入（Assembler），不在 Adapter 里写死「二次元」；多模型切换只换启用绑定，人设真源仍在 Companion。  
**多核心特殊化：** Adapter 无会话亲缘；任意核进程用同一 manage 启用模型即可。密钥只在 app 环境，不进 kernel、不进 Trace。

---

### 2.5 ContextAssembler · K01-B/C（策略 `OWNER: USER`）

| | |
|--|--|
| **契约** | 只读存储 → 不可变 `AgentInput`；**禁止**改 Message 原文 |
| **主参照** | G3 memory→messages；G5 condenser（裁剪思想）；L4 `systemPrompt.assemble` |
| **辅参照** | L3 build_turn_context（防胖：只借「回合序章」分层） |

**二次元 / 个人问答特殊化（本模块是主战场）**

- 拼装顺序建议：安全与人设 → 关系摘要 → 相关长期记忆 → 裁剪后近讯 → 当前用户句 → 可见工具描述。
- 裁剪：**永不截断当前用户句与安全/人设块**；优先丢低相关旧消息。
- Facet（日常陪伴 / 工作 / 创作等）只影响注入内容与语气偏好，不分裂 `CompanionIdentity`。
- 二次元表达（称呼、口癖、禁忌）来自 Companion 配置 + Relationship，不来自临时 prompt 魔法字符串散落。

**多核心特殊化**

- Assembler 只读 **身份真源库**（烟火一份）；禁止按 `kernelInstanceId` 分叉记忆视图。
- 影子核验证（Graduation）可用只读副本/影子状态，正式 SERVING 与 STANDBY **不得**各写一套关系。

---

### 2.6 TurnEngine · K01-C

| | |
|--|--|
| **契约** | 认领成功 → Assembler → Loop → Outcome→Plan → freeze |
| **主参照** | **L2 shell**（核外包编排）；**L1** Turn 认领/事件管道；合同 COMMITTING 协议 |
| **辅参照** | G5 `conversation.run` 外循环（只借「壳调 step」）；G1 Runner 外层 max_turns |

**二次元特殊化：** before/after 调 Companion 钩子（关系计数、相伴日等）在引擎，不在 Loop；失败时用户句走 ControlledFailure 映射，保持陪伴语气。  
**多核心特殊化（关键）**

- 今日：`executionId` + revision CAS（已有 H3 语义）。
- 导向 [宿主主备稿](./history/v02-multi-kernel.md)：日后 `claim(..., generationId) → FencingToken`；旧核迟到 Outcome **不得**提交。
- Engine 可驻留在 SERVING 核进程；**GenerationManager**（更后）只决定谁接新 Turn，不重写 Loop。

---

### 2.7 TurnCommitter · 已有（H2/H3）

| | |
|--|--|
| **契约** | freeze 完整计划；commit 同事务写 Message/Turn/Outbox |
| **主参照** | 本仓已放行协议；L1 提交边界（概念） |
| **勿参照** | G2 checkpointer 当提交器；G5 run 内持锁落盘当唯一真相 |

**二次元特殊化：** 助手正文与必需完成事件同事务，避免「关系变了但话没落库」。  
**多核心特殊化：** 冻结计划是 **跨核恢复真源**；换核/崩溃只重放计划，不重跑 Loop、不换烟火身份。

---

### 2.8 Cancel · K01-B 最小 / K01-C 竞态

| | |
|--|--|
| **主参照** | L2 AbortSignal；L1 AtomicBoolean；L5 cancel 点；G5 PAUSED |
| **辅参照** | L3 interrupt/steer（中途改口；v0.1 可只做取消） |

**二次元特殊化：** 取消后的可见文案避免「已执行副作用却说什么都没发生」；陪伴场景少危险工具，仍守 UNKNOWN 语义（K02）。  
**多核心特殊化：** 取消与 beginCommit 的决胜在 **持久化 CAS**；跨核不得各判各的。合法 COMMITTING 不因另一核的普通取消打回。

---

### 2.9 Error codes / 日志 · K01-A

| | |
|--|--|
| **主参照** | 合同 / 缺陷审阅稳定 code；G1 错误种类；G4 refusal/max_tokens 映射 |
| **勿参照** | 每层一套字符串（Controller / JDBC / 厂商各写各的） |

**二次元特殊化：** `code` 稳定；`safeUserMessage` 可人设化。日志无密钥、无倾诉正文、无堆栈刷屏。  
**多核心特殊化：** code 表 **全代际共用**；禁止「A 核一套码、B 核一套码」。

---

### 2.10 ToolRuntime + ToolPolicy · K02

| | |
|--|--|
| **契约** | `execute → ToolExecutionOutcome`；Policy/Validator **内部** |
| **主参照** | G4 client 工具循环；G2 ToolNode；L4 并行/exclusive；L1 Guard/propose-confirm |
| **辅参照** | G3/G5 沙箱（本机 worker 更后）；G1 computer tools（不照搬） |

**二次元 / 个人问答特殊化**

- v0.1 起工具集按 **ToolProfile** 切换：`chat.default` / `chat.mcp`（识图）/ `schedule`；日后 `im.bridge`、`app.api`、`game.*`（与对话目录隔离，对照 OpenGameAgent「游戏权威工具」）。
- observation 截断与脱敏；高危（IM/写操作）走确认（L1 propose-confirm 思想）。
- 游戏会话与陪伴会话换 profile，不换 Loop 实现。

**多核心特殊化**

- `operationId` 绑定 Turn/尝试；换核禁止双执行同一外部副作用。
- 世界线触发的工具（若有）仍经同一 ToolRuntime，权限由 profile 约束。

---

### 2.11 BackgroundPolicy + TaskRuntime · K05

| | |
|--|--|
| **主参照** | 工作簿后台条件；G1 Interruption/approval；G5 确认门闩；L3 长任务旁路 |
| **勿参照** | G1 Handoff 当「多人格烟火」 |

**二次元特殊化：** 长检索/整理可后台；**陪伴对话本身默认前台**，避免用户以为烟火「分心消失」。Acknowledgement 用人设短句。  
**多核心特殊化：** Task 归属 `CompanionIdentity` + fencing；换 SERVING 核要能接管或显式放弃 Run，禁止两核各跑同一 Task。

---

### 2.12 MemoryRuntime · K03

| | |
|--|--|
| **主参照** | 本仓 [memory-*](./README.md) 决策；G3 Agent memory 步骤思想；社区陪伴记忆仅作对照勿整搬 |
| **勿参照** | 把记忆读写塞进 Loop while |

**二次元特殊化：** 情感连续、称呼、喜好、禁忌；世界事件与主动私信也可作为证据来源（预留 `WORLD_EVENT` / `PROACTIVE_TURN`）。S0/S1/S2、冲突策略按已有 research。**K03 按核心路径排期，不可长期空壳假装陪伴完成。**  
**多核心特殊化：** 记忆绑定烟火身份，不绑定 Kernel 代际或 WorldAgent 进程。

---

### 2.13 RelationshipRuntime + Companion · K03 / 契约可先

| | |
|--|--|
| **主参照** | [memory.md](./memory.md)；产品 Companion 钩子；**无**通用开源「关系模块」可整抄 |
| **辅参照** | L1 产品规则外置思想（propose 等） |

**二次元 / 个人问答特殊化（产品核心差异）**

- `CompanionIdentity` = 烟火，永久；Facet = 模式（陪伴/工作/…），不是多个灵魂。
- RelationshipState = 互动契约 + 共同历史索引（称呼、阶段、相伴起点），**不是**游戏好感度刷分（MVP 不做复杂自动成长曲线）。
- before Turn：注入关系摘要；after Turn：允许有界更新（明确用户确认的关系定位等）。
- 问答助手：工作 Facet 可更短更克制；陪伴 Facet 更舒缓——经 Assembler，不经第二套 Loop。

**多核心特殊化**

- 关系与身份 **唯一真源**；A/B 核切换不得出现两个关系阶段或两份称呼。
- Kernel 不拥有烟火；只代表烟火执行（见多核审阅 §2）。

---

### 2.14 Channel · 内嵌对话 / SPI · 已有页面，接线随 K01-C/D、K04

| | |
|--|--|
| **主参照** | L5 / L3 IM·Gateway（入站统一成 Turn）；本仓 `/chat/` |
| **勿参照** | Client 内再实现 ReAct 主循环 |

**二次元特殊化：** 内嵌页是正式对话入口；语气与人设在服务端注入，页面不拼系统提示。日后 Bot 通道同库，仍是一个烟火。  
**多核心特殊化：** Channel 只打到 **当前 SERVING** 路由；客户端不感知 A/B。

---

### 2.15 Outbox + SSE · K04

| | |
|--|--|
| **主参照** | 本仓 Outbox 合同；L2 事件流形状；G1 tracing（仅对照字段） |
| **勿参照** | Loop 内直接推 SSE |

**二次元特殊化：** 流式可增强「陪伴感」，但 **正式完成** 仍以 commit 为准，避免半句关系承诺。  
**多核心特殊化：** 完成事件带 ownership/fencing；旧核禁止补发覆盖新核事实。

---

### 2.16 Manage / Model 装配 · 已有 H4

| | |
|--|--|
| **主参照** | 本仓 manage；Open WebUI 分栏思想（历史批次记录） |
| **特殊化** | `probe` ≠ 陪伴会话；人设/关系不在 probe 里改 |

**多核心：** 模型启用配置属平台；毕业核与服务核可共享同一模型绑定，身份数据仍唯一。

---

### 2.17 GenerationManager / 宿主主备 · 更后（勿与多核心拓扑混淆）

| | |
|--|--|
| **主参照** | [history/v02-multi-kernel.md](./history/v02-multi-kernel.md) |
| **含义** | **同一角色（通常烟火）** 制品 SERVING/STANDBY 换代 |
| **不是** | 增加世界树节点、或「双核变三核」 |

多核心拓扑见 [multi-node-companion-world.md](./multi-node-companion-world.md)。

## 3. 模块总表（参照速查）

| 模块 | 批次 | 主参照 Agent | 二次元特殊化要点 | 多核心特殊化要点 |
|------|------|--------------|------------------|------------------|
| AgentLoop | K01 | G4, G1, L2 | 问答默认无工具；文案人设化 | 无代际分支；可换制品 |
| AgentBudget | K01 | G1, G3, L1 | 紧预算、温和超时文案 | 属单次尝试 |
| Input/Outcome/Trace | K01 | G1, G4 | 人设/关系槽位；Trace 脱敏 | 计划可跨核提交 |
| ModelPort | 已有 | G4, 本仓 | 人设不在 Adapter | 无状态适配器 |
| ContextAssembler | K01 | G3, G5, L4 | **人设·关系·记忆主注入** | 只读唯一身份库 |
| TurnEngine | K01 | L2壳, L1 | Companion 钩子在壳 | fencing / 单所有者 |
| TurnCommitter | 已有 | 本仓 | 消息与事件同事务 | 冻结计划跨核恢复 |
| Cancel | K01 | L2, L1 | 副作用诚实 | CAS 跨核一致 |
| Error/Log | K01 | 合同, G4 | code≠文案 | 全代际共用 code |
| ToolRuntime | K02 | G4, G2, L4, L1 | 低危只读优先 | operationId + 禁双执行 |
| Background/Task | K05 | 工作簿, G5 | 陪伴默认前台 | Task 随身份+fence |
| Memory | K03 | [memory.md](./memory.md) | 情感连续与敏感分级 | 绑定烟火非代际 |
| Relationship/Companion | K03 | [memory.md](./memory.md) | 烟火身份+Facet | 唯一关系真源 |
| Channel | 已有/K04 | L5, L3 | 服务端注入人设 | 只达 SERVING |
| Outbox/SSE | K04 | 本仓, L2 | 完成以 commit 为准 | 事件带 ownership |
| GenerationManager | 更后 | history 主备稿 | 用户只见烟火 | 主备轮换非多活 |

---

## 4. 批次

不在这里维护顺序。唯一排期：[roadmap.md](../plans/roadmap.md)。N01 验收在 [多核心拓扑](./multi-node-companion-world.md)。

---

## 5. 包与依赖

```text
com.wannian.server.kernel.agent     ← Loop, Input, Outcome, Budget, Trace, Assembler
com.wannian.server.kernel.model     ← ModelPort
com.wannian.server.kernel.turn      ← TurnEngine, Committer（预留 fencing 字段）
com.wannian.server.kernel.tool      ← ToolRuntime（K02）
com.wannian.server.kernel.memory    ← Memory + Relationship（K03）
com.wannian.server.kernel.companion ← 人设快照 / before-after（可与 memory 同包，勿进 agent.loop）
com.wannian.server.kernel.task      ← K05
com.wannian.server.app.*            ← Adapter、Channel、日后 Generation 宿主
```

```text
AgentLoop  ↛  Repository / DataSource / Controller / GenerationManager
TurnEngine → agent + committer +（只读）companion/memory
更后 GenerationManager → 只路由「谁调 TurnEngine」，不调 ModelPort 绕过 Loop
```

---

## 6. 分工与验收口令

| 模块 | 框架可搭 | `OWNER: USER` |
|------|----------|---------------|
| 类型、Budget、Trace、Engine 接线、测试 | 是 | — |
| `DefaultAgentLoop.run`、Assembler 策略、空答/拒绝人设文案 | 骨架 | **是** |
| Memory / Relationship / Companion 策略 | 骨架 | **是（K03）** |

写类前三问：

1. 循环还是模块？  
2. 参照的是哪几个 Agent、有没有照抄不该抄的 Persist/多 Agent？  
3. 是否破坏「烟火唯一」或「Turn 单所有者」？

---

## 7. 相关文档

| 文档 | 用途 |
|------|------|
| [agent-loop-survey.md](./agent-loop-survey.md) | 十方抽象与对照 |
| [multi-node-companion-world.md](./multi-node-companion-world.md) | 设备 / 节点 / 角色 |
| [world-evolution-and-extensions.md](./world-evolution-and-extensions.md) | 世界叙事、工具画像、Jev |
| [k01-agent-loop.md](../plans/k01-agent-loop.md) | K01 施工 |
| [roadmap.md](../plans/roadmap.md) | 全档排期 |
| [memory.md](./memory.md) | 记忆与关系已确认边界 |
| [02-modules.md](../product/02-modules.md) | 产品级模块表 |
