# 03 · 0.2.1 审计（Agent Loop / Turn 接线 / 错误码）

审查日期：2026-09-22。对象：当前工作树中宣称 **v0.2 · 0.2.1 已交付** 的 `wn-server` 实现（A/B/C/D），对照 [实施清单 0.2.1](../guide/01-checklist.md)、[施工单](../plans/k01-agent-loop.md)、[插话决策](../research/in-flight-user-message.md)、[内核合同 followup](../decisions/01-contract.md)。

性质：审阅与缺陷登记，**本次不修改业务代码，不启动修复，不授权提交、发布或扩大阶段范围**。旧稿 [01-defects](./01-defects.md) / [02-release-h3](./02-release-h3.md) 不改写。

Git HEAD：`e98252d603fd8754c93145aa6a75dc09095c9a5f`（已提交基线仍为 v0.1 树）。0.2.1 相关改动主要在**未提交工作树**；不能用空的 `git diff HEAD` 声称「无代码」。下文以审查时源码为准。

关键源码 SHA-256（判断是否仍是本快照）：

```text
DefaultAgentLoop.java
30119BB50804B3BF69F4C4164B651F2A81F1D69784B69B59DDF797CDFDE7D924
TurnEngine.java
DE7BF6D8FD7515095F3DFBCFA2A4F7FCD4F6AB97EA2F982F25B5FBABE1B9C38F
TurnController.java
4A1B99325A9667FAEDDB884C577743C4DE28B980DD00E28E23F1B194CE8B198B
TimeoutModelPort.java
4A28A1878BB8505F145526790137E12243FD55050931454D9AE183391B9E9F37
```

---

## 1. 结论

0.2.1 的**主路径已接通**：`receive` → `TurnEngine`（claim → Assembler → `DefaultAgentLoop` → freeze → commit）→ HTTP `reply`；错误码登记处存在；live 夹具与编排测试覆盖了同键不重跑、COMMITTING 恢复、STALE_ATTEMPT 不写助手消息等关键不变量。方向正确，不需要推倒重写。

但相对施工单与已定 **followup（FIFO）** 合同，当前交付有若干**正确性缺口**；清单上部分 `[x]` 过宽（把「可配置」或「同键幂等」当成「软预算生效」或「同会话单 flight」）。**建议：在宣称 0.2.1 收口或开工 0.2.2 工具循环前，至少关闭本文 P1；P2 可与 0.2.2/0.2.4/0.2.7 排期对齐，但不得 silently 当已交付。**

| 方面 | 判断 |
|------|------|
| B 直接回答（live 单次 decide） | 结构成立；blank / 决策打满仍为已知待补 |
| C Turn 编排 | 正常路径与部分竞态有测；freeze revision 冲突与中途失败收口不完整 |
| D HTTP execute | 已退役 `TurnDialogue`；未启用模型停 RECEIVED 成立 |
| A 错误码 | 登记处成立；`SafeErrorLog` 未接线；适配器仍散落字面量 |
| followup / 同会话串行 | **未实现**；双异键 POST 可双跑 Loop |
| 预算闸门 | 硬截止有入口检查；软截止死字段；次数闸门在单步收口下不可达 |
| 取消 | 仅 decide 前令牌；无产品 cancel 路径；适配器 CANCELLED 落 FAILED |

优先级：P1 = 应在依赖它的后续功能（工具多步、SSE 插话、可靠恢复）前修复；P2 = 边界 / 可维护 / 资源；P3 = 低影响仍值得记。本轮**无证据支持 P0 全系统崩溃**；双标签并发下的双跑属于高概率产品级错误，标为 P1。

---

## 2. 范围与证据口径

- **静态确定**：由实际代码路径直接得出（含对照施工单验收句）。
- **动态**：本轮未重跑 live 外网套件；编排结论引用现有 `TurnEngineOrchestrationTest` / 施工单登记命令的设计意图。修复验收须另跑窄测，不得借用本文当通过证据。
- **已知待补（施工单已标 `[ ]`）**：blank / Refusal / Failure 受控失败的 live 难触发；`maxModelDecisions=3` 打满难触发。本文仍指出「配置存在但闸门不可达」与「清单把软预算勾成已交付」的过宽问题。
- 排除 `target/`；不审查未改动的历史 H3 已关闭项（除非 0.2.1 接线破坏其不变量）。

主要阅读：`kernel/.../agent/*`、`kernel/.../turn/TurnEngine.java`、`kernel/.../error/*`、`app/.../http/TurnController.java`、`TimeoutModelPort` / `OpenAiCompatibleModelAdapter` / `AgentBudgetSettings`、相关测试与 `docs/plans/k01-agent-loop.md`。

---

## 3. Findings（按严重度）

### F-01 · P1：同会话 followup / 单 RUNNING 未实现，异键双 POST 可双跑 Loop

**合同：** [in-flight](../research/in-flight-user-message.md) §11、[01-contract](../decisions/01-contract.md)、k01 文首：「同会话 followup（FIFO）；双 POST 不双跑」。清单勾选「同会话执行顺序与上下文一致」仅旁注 `ReceiveTurnIdempotency` **同键**并发，不能覆盖异键插话。

**事实：** `receive` 对同一 `conversationId`、不同 `clientRequestId` 会各建一条 RECEIVED Turn；`TurnController.finishAccepted` 对每条 RECEIVED 直接 `turnEngine.execute`；全仓无「会话已有 CLAIMED/RUNNING 则只入队不执行」的闸门（无 SQL/`FOR UPDATE`/调度器命中）。

**场景：** 双标签或 IM 连发两句 → 两个 HTTP 线程并行 `DefaultAgentLoop.run` → 双模型调用、近讯交错、回复次序不可解释。单页 `pending` 挡不住双端。

**建议方向（供后续任务，本轮不实施）：** 执行层「同会话最多一个非终态 flight」；新话 receive 仍落库（不拒收），execute 仅在无活跃 flight 时 claim；A 终态后再 claim B。测试：同会话两异键并发，断言 `decide` 计数串行且第二 Turn 在第一终态后才进 RUNNING。

---

### F-02 · P1：`softDeadline` 可配置但 Loop 从不读取

**证据：** `AgentBudget` 携带 `softDeadline`；管理页 / `wannian.json` 可改；`DefaultAgentLoop.blockBeforeDecide` 只检查 `cancelToken`、`maxModelDecisions`、`hardDeadline`。清单将「15 秒软预算、30 秒硬上限可配置」勾 `[x]`——配置写入 ≠ 行为生效。

**影响：** 产品以为改软截止会改变收口策略；实际软截止是死字段。与 `AgentBudget` 文档「到达后宜收口或告警」不符。

---

### F-03 · P1：`maxModelDecisions` 闸门在现行 Loop 不可达

**证据：** `DefaultAgentLoop.run` 为 `while (true)`，但四个 `ModelOutcome` 分支均 `return`；`ToolCalls` 不 `continue`。`completedDecisions` 在返回前至多为 1，故 `completedDecisions >= budget.maxModelDecisions()`（通常 3）对生产路径不可达。施工单将打满标为待补合理，但管理页仍暴露「最多 decide 次数」且清单未区分「字段已存」与「闸门已验」。

**影响：** 0.2.2 一旦对 ToolCalls 继续循环，若未补测，可能把未验证闸门当成已交付。当前属**预埋失效**，不是「工具延后所以无所谓」。

---

### F-04 · P1：适配器 `CANCELLED` 落成 `ControlledFailure` → Turn `FAILED`，不是 `Cancelled`

**证据：**

1. `TimeoutModelPort` / `OpenAiCompatibleModelAdapter` 在 `context.cancelled()` 时返回 `ModelOutcome.Failure(CANCELLED, …)`。
2. `DefaultAgentLoop` 将所有 `Failure` 映射为 `ControlledFailure`。
3. `TurnEngine` 对 `ControlledFailure` 调 `failAttempt`（→ FAILED）；仅 `AgentOutcome.Cancelled` 走 `cancelAttempt`。

**影响：** 即便在 decide 入口已看见取消，回合仍进 FAILED 而非 CANCELLED；与预算令牌语义、状态机「取消」路径不一致。另：生产 HTTP **未暴露** 任何置位 `cancelToken` 的入口（令牌在 `createBudget` 后仅传入 execute），显式 Stop 合同未接线——与 F-04 叠加为「取消能力名义存在、路径不通」。

---

### F-05 · P1：`freezeCommit` 的 `RevisionConflict` 不收口，Turn 可留在 RUNNING

**证据：** `TurnEngine.sealReply` 在 `FreezeCommitResult.Frozen` 成功路径提交；`Rejected` 会 `failAttempt`；**`RevisionConflict` 只返回 Held，不 fail/cancel**。其后 `execute` 对 CLAIMED/RUNNING 直接 `ILLEGAL_STATUS` Held。

**叠加：** `Turn.claim` 仅允许自 RECEIVED；lease 过期也不能经 TurnEngine 重新 claim。进程中断、冲突或客户端同键重试撞上 RUNNING 时，易成**僵尸 RUNNING**（完整进程强杀属 0.2.7，但本路径无需强杀即可出现）。

**建议：** conflict/失败路径统一 `failAttempt` 或可恢复的 reclaim 策略；对 COMMITTING 继续只走冻结计划；补测「freeze revision 冲突后 status ≠ RUNNING」。

---

### F-06 · P2：`TimeoutModelPort` 每调用新建线程池，超时不取消内层 HTTP

**证据：** 每次 `decide` `Executors.newSingleThreadExecutor()`；`TimeoutException` 后 `shutdownNow()`，但内层 `HttpClient.send`（固定 30s `HttpRequest.timeout`）不感知 Future 取消；`OpenAiCompatibleModelAdapter` 忽略 `context.deadline()`，硬编码 30s。

**影响：** 外层已返回 `MODEL_TIMEOUT` 后，出站调用与线程仍可能跑满；配额浪费、连接堆积。硬预算与 HTTP 超时双轨。

---

### F-07 · P2：厂商 Refusal / Failure 原文进入用户可见 `detail`

**证据：** Loop 把 `refusal.reason()` / `failure.detail()` 原样写入 `ControlledFailure.safeUserMessage`；`TurnController` 经 `Held.detail` 回给 `/chat/`。无统一消毒（对比 `ErrorLogFields.redact` 只用于未接线的日志类型）。

**影响：** 解析错误、HTTP 状态文案、供应商拒答原文可进 UI；与「日志/用户通道脱敏」目标不一致（密钥形态未必出现，但敏感/噪音会）。

---

### F-08 · P2：`SafeErrorLog` / `ErrorLogFields` 未接入业务路径

**证据：** 仓内无 `SafeErrorLog.` 调用点；类型与单测存在，边界适配器仍可能 `log.error(..., ex)` 或根本不记结构化字段。清单「日志约定」勾选偏「类型已有」，非「调用点已统一」。

---

### F-09 · P2：`DecisionPort` + `NoopDecisionPort` 缺失

**证据：** k01 B0 / §9 要求接口 + Noop（无 Jev）；`wn-server` 无对应 `.java`。本批可不调用，但「契约预留」未落地；与「已交付」表述不完全一致。属范围缺口，非运行时崩溃。

---

### F-10 · P2：`wannian.json` 损坏时可能整文件被空对象覆盖

**证据：** `AgentBudgetSettings.objectRoot`：JSON 解析失败则 `createObjectNode()` 再 `writeBudget`，只保留新的 `agentBudget`。同 JVM 有锁；跨进程与损坏恢复会丢掉同文件其它键。

---

### F-11 · P2：适配器错误码字面量绕开 `ErrorCodes` 引用纪律

**证据：** `OpenAiCompatibleModelAdapter` 使用 `"CANCELLED"`、`"MODEL_RATE_LIMITED"` 字符串及 `ManageReason.*`；功能上常与登记值相同，但违反「禁止再各写一套」的维护约束，易漂移。

---

### F-12 · P3：`AgentTrace` 远低于 guide §15 字段要求

仅一步说明字符串；无 model / usage / duration / tool / operationId。审计与预算调试弱。可随 0.2.2 一并加强。

---

### F-13 · P3：空 `ToolCalls` 与 guide 期望码不一致

guide 对「无 calls 的工具决策」期望偏 `INVALID_MODEL_OUTPUT`；实现一律 `TOOLS_NOT_ENABLED`。0.2.1 占位可接受，但应在 0.2.2 拆分，避免误导排障。

---

## 4. 清单 / 施工单过宽勾选（审计备注）

下列 `[x]` 建议在修复或明确降级表述前视为**证据不足**，不是要求立刻改清单正文（清单改写另授权）：

| 勾选项 | 问题 |
|--------|------|
| 15s 软 / 30s 硬可配置 | 硬截止有 decide 前检查；软截止无行为（F-02） |
| 同会话执行顺序…（旁注同键幂等） | 未证明异键 FIFO / 单 RUNNING（F-01） |
| 全进程日志约定 | 类型在；`SafeErrorLog` 未用（F-08） |
| cancel 在 decide 前 → Cancelled | 单测夹具可；生产无 cancel API；适配器 CANCELLED→FAILED（F-04） |

施工单已诚实保留的 `[ ]`（blank / 决策打满）与本文 F-03 一致，保留即可。

---

## 5. 已成立、勿回退的部分

- 三层依赖方向：Loop 无 Spring/JDBC/SDK import（抽查 `DefaultAgentLoop`）。
- `ContextAssembler`：当前用户句与 excerpt 分离；编排测覆盖缩进原文。
- 同 `clientRequestId` 完成后二次 `execute` → `AlreadyCompleted`，RecordingLoop 计数不增。
- COMMITTING + 冻结计划恢复不调 Loop。
- Loop 后 `executionId` 被换 → `STALE_ATTEMPT`，无助手消息。
- 未启用模型：RECEIVED + reason，页面无 `reply` 不当已答。
- `ManageReason` 已改为 `ErrorCodes` 别名；`ErrorCodes` 登记表存在且自测。
- `TurnDialogue` 已删；HTTP 走 `TurnEngine`。

---

## 6. 测试缺口（相对缺陷）

| 缺口 | 对应 |
|------|------|
| 同会话两异键并发 execute，断言单 flight | F-01 |
| softDeadline 到达后的可观察行为（收口/标记） | F-02 |
| ToolCalls `continue` 后第 N+1 次 decide 前 `BUDGET_EXHAUSTED`（可等 0.2.2，但闸门单测可先做） | F-03 |
| `Failure(CANCELLED)` → 领域 `Cancelled` / DB `CANCELLED` | F-04 |
| freeze `RevisionConflict` 后 status 与可恢复性 | F-05 |
| Timeout 后内层 HTTP 是否仍占用（资源探针） | F-06 |
| 显式 cancel HTTP/夹具（guide §11） | F-04 + 产品路径 |

本轮未宣称重跑：

```text
TurnEngineOrchestrationTest, TurnEngineLiveCTest, TurnEngineHttpLiveDTest, DefaultAgentLoopLiveTest, …
```

修复后应按 [local-verify-fast](../../../../.agents/rules/local-verify-fast.md) 窄测上述类，live 仍禁 fake。

---

## 7. 与后续版本边界

| 项 | 归属 |
|----|------|
| ToolRuntime / 多步 continue | 0.2.2（但 F-03/F-13 应在接线前收口闸门） |
| Memory / 关系注入 | 0.2.3（Assembler 空字段属预期） |
| SSE、聊天历史恢复、队列 UI | 0.2.4（F-01 协议层应更早） |
| 进程强杀、整库堆峰值 | 0.2.7（F-05 的非强杀僵尸仍属 0.2.1 债） |

---

## 8. 建议修复顺序（给执行者，非本轮实施）

1. **F-01** 同会话单 flight + receive 不拒收（合同硬约束）。
2. **F-05** freeze/失败路径收口，避免僵尸 RUNNING。
3. **F-04** `CANCELLED` Outcome 映射 +（可并行）最小 cancel 夹具；产品 Stop 可后置但映射须先对。
4. **F-02 / F-03** 软截止语义（哪怕仅 trace/提前收口）与次数闸门可测性（单测可用可控 ModelPort，不与「Loop 行为验收禁 fake」冲突——验收 live 另论）。
5. **F-06–F-11** 按风险插入 0.2.2 前后。

执行者交付：实际 diff、窄测命令与退出码、逐条 F-xx 关闭证据；扩大范围先回报。

---

## 9. 一句话裁决

**0.2.1 主路径可演示，但 followup 串行、软预算、取消语义与部分失败收口未达合同；清单若干 `[x]` 过宽。先关 P1，再把「已交付」当作可依赖基线。**
