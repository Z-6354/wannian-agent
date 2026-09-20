# 调研 · Agent = 一个循环 + 其他模块

`status`: **reference** — 2026-09-20（同日修订：按「一循环 + 模块」重梳）  
`purpose`: 抽出十方共同本质，再用同一模板拆每个 Agent，供 wannian K01 定边界。细节证据仍来自本机源码与 GitHub 一手材料。  
`not-authority`: 不改写合同 / 工作簿；K01 实施以 [施工单](../plans/k01-agent-loop.md) 为准，全档顺序见 [roadmap](../plans/roadmap.md)。

---

## 1. 本质抽象

### 1.1 一句话

**Agent 产品 = 一个决定循环（Loop）+ 一组可替换模块（Modules）。**  
差异几乎从不在「要不要循环」，而在：**循环瘦/胖、模块切在哪、谁持有状态、谁落盘。**

### 1.2 唯一循环（所有 Agent 同构）

```text
while 未结束:
    decision = Model.decide(当前上下文, 可见工具, 剩余预算)
    if 最终回答 / 拒绝 / 不可恢复失败:
        return Outcome
    if 工具调用:
        observations = Tools.execute(calls)
        上下文 += observations
        continue
    # 其它：切换 Agent、暂停确认、预算尽 → 仍是「结束或再进 while」
```

这就是全部「智能」回合逻辑。Anthropic 写成 `while stop_reason == tool_use`；OpenAI 写成 `while` + `NextStep*`；LangGraph 写成图上的 `agent ⇄ tools`——**拓扑不同，循环同一。**

### 1.3 模块目录（循环外的一切）

| 模块 | 职责 | 典型名字 |
|------|------|----------|
| **Model** | 一次补全 / 流式；产出 Final / ToolCalls / Refusal / Failure | ModelPort、LLM client、streamAssistant |
| **Tools** | 执行调用、幂等、截断 observation、沙箱 | ToolRuntime、ToolNode、PythonExecutor |
| **Budget** | 次数 / 时间 / token / 美元上限与告警 | AgentBudget、max_turns、max_steps、grace |
| **Context** | 拼系统提示、历史裁剪、记忆注入；**不改原文落库** | ContextAssembler、systemPrompt、condenser |
| **Session / Persist** | 认领、提交、检查点、会话落盘 | TurnEngine、checkpointer、conversation.save |
| **Cancel / Steer** | 取消、用户中途改口、注入新消息 | AbortSignal、interrupt、steering、redirect |
| **Events / Trace** | 流式块、审计步、telemetry | TurnEvent、agent_start/turn_end、LangSmith |
| **Policy / Guard** | 工具准入、propose-confirm、guardrails、stuck | AgentRunGuard、StuckDetector、guardrails |
| **Multi-Agent** | 交接、子代理 | Handoff、subagent |
| **Channel / Host** | HTTP、IM、桌面、workspace | Gateway、embedded runner、LocalConversation |

**判别标准：** 若删掉某段代码后，「decide → 工具 → 回灌 → 再 decide」仍成立，它就不是 Loop，而是模块。

### 1.4 总图

```text
                 ┌─ Context ──────────────┐
                 │  Budget  Cancel/Steer  │
 Channel/Host ──►│                        │
 Session/Persist │      ┌──────────┐      │──► Events/Trace
                 │      │  LOOP    │      │
                 │      │ decide ↔ │◄─────┼── Model
                 │      │  tools   │      │
                 │      └──────────┘      │──► Tools（含沙箱）
                 │  Policy/Guard          │
                 │  Multi-Agent（可选）   │
                 └────────────────────────┘
```

wannian 目标切法：

```text
Loop          = 上图中心 while + 返回 AgentOutcome
TurnEngine    = Session/Persist + 调用 Loop
ContextAssembler / ModelPort / ToolRuntime / Budget / Trace = 模块
```

---

## 2. 梳理模板（每个 Agent 同一张表）

对每个 Agent 只答四问：

1. **Loop 在哪**（文件 / 函数，瘦到什么程度）  
2. **循环本体**（伪代码一行级）  
3. **模块落点**（上表各项：内嵌在 Loop 文件里，还是独立模块）  
4. **边界评价**（对 wannian：哪些该进 kernel Loop，哪些必须外置）

---

## 3. 本机五个

### L1 · HAN Agent

| 问 | 答 |
|----|----|
| Loop | `ReactAgentLoop.run` |
| 本体 | `while budget → completeWithTools → toolInvoke 回灌 \| 返回文本` |
| 边界 | 治理与 Turn 事件偏胖；Outcome 非 sealed |

**模块落点**

| 模块 | 落点 |
|------|------|
| Model | `LlmClient.completeWithTools`（循环调用） |
| Tools | `ToolCallModule.invoke`（循环内直接调） |
| Budget | `AgentBudget` + warn nudge（**贴在 Loop 旁**） |
| Context | Turn 编排拼 `initialMessages` 后传入 |
| Session | **Loop 外** Turn 编排 / 持久化 |
| Cancel | `AtomicBoolean`（Loop 内轮询） |
| Events | `TurnEvent` 流式（**Loop 内发**） |
| Policy | `AgentRunGuard`、propose-confirm、parked（**Loop 内**） |
| Multi-Agent | 无 |
| Channel | 更外层网关 |

**一循环 + 模块观：** 循环清楚，但 Policy / Events / Tools 都焊在 Loop 类里 → **胖循环**。wannian 应把 Guard/propose 收到 ToolPolicy，事件留给 Turn/SSE。

---

### L2 · OpenClaw（agent-core + embedded shell）

| 问 | 答 |
|----|----|
| Loop | `agent-loop.ts` 双层 while；壳 `embedded-agent-runner/run-loop.ts` |
| 本体 | `streamAssistant → toolUse? executeToolCalls 回灌 : 结束`；外层可再接 steering |
| 边界 | **核壳分离最好范例** |

**模块落点**

| 模块 | 落点 |
|------|------|
| Model | `streamAssistantResponse` |
| Tools | `executeToolCalls` + tool-loop recovery |
| Budget | 核内弱；**重试 / failover / compaction 在壳** |
| Context | `state.context.messages`（核内可变） |
| Session | 壳 / 会话层 |
| Cancel | `AbortSignal` |
| Events | `agent_start` / `turn_*` / `agent_end` |
| Policy | tool-loop terminate（核）；权限变更（壳） |
| Steer | pendingMessages 注入（核） |
| Channel | embedded runner 宿主 |

**一循环 + 模块观：** 同一循环，Failover/Compaction 明确是**其他模块**。wannian：`AgentLoop` ≈ core；`TurnEngine`/app ≈ shell。

---

### L3 · Hermes

| 问 | 答 |
|----|----|
| Loop | `conversation_loop.run_conversation` |
| 本体 | `while (迭代预算 \|\| grace) → API → 工具并发回灌 \| finalize` |
| 边界 | **单文件吞掉几乎所有模块** → 反面教材 |

**模块落点**

| 模块 | 落点 |
|------|------|
| Model | API call（循环内） |
| Tools | validate / dispatch / 并发（循环内） |
| Budget | max_iterations + iteration_budget + **grace**（循环条件） |
| Context | prologue `build_turn_context`；压缩穿插 |
| Session | `_persist_session`（循环缝） |
| Cancel | interrupt / redirect / steer（循环缝） |
| Events | step_callback |
| Policy | skill nudge、非法工具 strike、review 预算 |
| Multi-Agent | 可选 codex_app_server **旁路整段循环** |
| Channel | gateway 设预算后调用 |

**一循环 + 模块观：** 循环还在，但 Context/Session/Policy/Steer 全挤进同一 while → 难测、难搬。wannian 禁止这种文件级膨胀。

---

### L4 · DeepSeek Harness

| 问 | 答 |
|----|----|
| Loop | `Agent.kick → while turn → while step` |
| 本体 | `preStep → model → toolCalls? execute→inbox : completed` |
| 边界 | 循环被 **Turn/Step 会计 + Inbox** 包住，仍是一循环 |

**模块落点**

| 模块 | 落点 |
|------|------|
| Model | `step()` 内 stream |
| Tools | `executeToolCalls`（并行池 / exclusive） |
| Budget | step/turn 计数；max-tokens sticky |
| Context | `systemPrompt.assemble`（preStep） |
| Session | session 事件日志 `turn/start`… |
| Cancel | AbortController |
| Events | dispatch `agent/error` 等 |
| Policy | plugin waterfall `agent/pre-step` |
| Steer | inbox claim / wake |
| Channel | Cordis 宿主 |

**一循环 + 模块观：** Inbox/Plugin 是模块，不是第二种循环。wannian 可借 turn/step **trace 编号**，不必引入 Cordis。

---

### L5 · CowAgent

| 问 | 答 |
|----|----|
| Loop | `AgentStreamExecutor.run_stream` |
| 本体 | `while turn < max → LLM → 无工具则结束，有则执行回灌` |
| 边界 | 循环瘦；IM/MCP 是外围 |

**模块落点**

| 模块 | 落点 |
|------|------|
| Model | `_call_llm_stream` |
| Tools | `_execute_tool` / 并行 |
| Budget | `max_turns` |
| Context | `self.messages`；MCP schema 集合 run 内增长 |
| Session | 对话持久化在外层 |
| Cancel | `_check_cancelled` |
| Events | `turn_start` / `turn_end` |
| Policy | 空回复补救二次提示；loop-detection hint |
| Steer | `_drain_steering`（优先于未完成工具） |
| Channel | IM / Web |

**一循环 + 模块观：** 教科书级瘦循环 + Channel/Steer 模块。空回复补救属 Policy，可进 ControlledFailure 策略而非焊死循环。

---

## 4. GitHub 精选五个

### G1 · OpenAI Agents SDK

| 问 | 答 |
|----|----|
| Loop | `Runner`：`while True` + `run_single_turn` |
| 本体 | `decide → NextStep(Final \| RunAgain \| Handoff \| Interrupt)` |
| 边界 | **Outcome 枚举即模块接口**；Session/Guardrails 易缠进 Runner |

**模块落点**

| 模块 | 落点 |
|------|------|
| Model | `run_single_turn` 内 |
| Tools | turn 内执行后 `RunAgain` |
| Budget | `max_turns` |
| Context / Session | session、conversation_id、RunState |
| Cancel | Interruption / approval |
| Events | 内置 tracing |
| Policy | input/output **guardrails** |
| Multi-Agent | **Handoff**（换 current_agent，仍同一 while） |
| Channel | 调用方 |

**一循环 + 模块观：** Handoff 不是新循环，是循环内「换 Model+Tools 配置」。wannian 借 `NextStep`→`AgentOutcome`；Session/Guardrails 外置。

---

### G2 · LangGraph ReAct

| 问 | 答 |
|----|----|
| Loop | 图：`agent` ⇄ `tools`（条件边） |
| 本体 | `call_model → tool_calls? ToolNode : END` |
| 边界 | 图 = 循环的另一种写法；**checkpointer 是 Persist 模块** |

**模块落点**

| 模块 | 落点 |
|------|------|
| Model | agent 节点 |
| Tools | ToolNode（可 Send 并行） |
| Budget | recursion_limit / remaining_steps |
| Context | `AgentState.messages` |
| Session | **checkpointer / store**（常与图绑死） |
| Cancel | interrupt_before/after |
| Events | LangSmith 等 |
| Policy | post_model_hook 等 |
| Multi-Agent | 多图 / 子图（另一产品形态） |

**一循环 + 模块观：** 不要被「图」迷惑——仍是一个循环。wannian 只借拓扑，**Persist 绝不能进 Loop**。

---

### G3 · smolagents

| 问 | 答 |
|----|----|
| Loop | `MultiStepAgent._run_stream` |
| 本体 | `while steps → generate → (工具\|代码) → memory → final_answer?` |
| 边界 | CodeExecutor 是 **Tools 模块的一种实现**，不是第二种循环 |

**模块落点**

| 模块 | 落点 |
|------|------|
| Model | `model.generate` |
| Tools | ToolCalling **或** PythonExecutor（沙箱） |
| Budget | `max_steps` + 强制 `provide_final_answer` |
| Context | `memory.steps` → `write_memory_to_messages` |
| Session | memory 对象（进程内） |
| Policy | planning_interval、final_answer 工具 |
| Channel | 调用方 / 托管 |

**一循环 + 模块观：** Code-as-action = Tools 插件。K01 用 JSON tools；沙箱属 K02+ 的 Tools 实现。

---

### G4 · Anthropic tool-use 契约

| 问 | 答 |
|----|----|
| Loop | 应用：`while stop_reason == tool_use`（BetaToolRunner 同构） |
| 本体 | `messages.create → tool_use? 执行回灌 : 退出` |
| 边界 | **最纯的「一循环」**；Server tools 是云端另一模块 |

**模块落点**

| 模块 | 落点 |
|------|------|
| Model | Messages API |
| Tools | **应用执行**（client）或 **Anthropic 执行**（server） |
| Budget | max_iterations；server 侧另有 pause_turn |
| Context | 调用方维护 messages |
| Session | 调用方 |
| Policy | stop_reason 语义（refusal / max_tokens） |
| Channel | 调用方 |

**一循环 + 模块观：** 官方直接定义「循环是应用的事，模型只出决策」。与 wannian `ModelPort` + `AgentLoop` + 外置 Turn 一致 → **主参照**。

---

### G5 · OpenHands SDK

| 问 | 答 |
|----|----|
| Loop | `LocalConversation.run` + `Agent.step` |
| 本体 | `while 状态机允许 → step(LLM 或执行挂起动作)` |
| 边界 | Workspace/确认/Stuck 是模块；`run` 看起来像「大状态机」，内核仍是一步 decide/act |

**模块落点**

| 模块 | 落点 |
|------|------|
| Model | `make_llm_completion` |
| Tools | workspace 沙箱执行 |
| Budget | max_iteration_per_run、美元预算 |
| Context | condenser、state.view |
| Session | conversation 状态 + 落盘（**run 持锁**） |
| Cancel | PAUSED |
| Policy | StuckDetector、WAITING_FOR_CONFIRMATION |
| Events | 事件流状态机 |
| Channel | Agent Server / UI |

**一循环 + 模块观：** 确认门闩与 Stuck 是 Policy；Workspace 是 Tools。wannian 不要把 conversation 落盘放进 Loop。

---

## 5. 十方对照（只看切分）

| Agent | 循环瘦度 | 焊进 Loop 最多的模块 | 切得最干净的模块 | 对 wannian 启示 |
|-------|----------|----------------------|------------------|-----------------|
| L1 han-agent | 中 | Policy、Events、Tools | Session（在 Turn） | 拆 Policy；留预算/取消 |
| L2 OpenClaw | 高（核） | Steer、Events | Failover 在壳 | **核壳分离照抄边界** |
| L3 Hermes | 低 | 几乎全部 | Channel 入口 | 反面：禁止单文件吞模块 |
| L4 DSH | 中 | Inbox、Plugin | — | 借 turn/step 会计 |
| L5 CowAgent | 高 | 少 | Channel/IM | 瘦循环样本 |
| G1 OpenAI | 中高 | Session、Guardrails 易缠 | Handoff 作分支 | Outcome 枚举 |
| G2 LangGraph | 中（图） | checkpointer 常绑死 | ToolNode 清晰 | 拓扑可借，Persist 外置 |
| G3 smolagents | 中 | Memory 对象 | Executor 可换 | Tools 可插拔 |
| G4 Anthropic | **最高** | 无（契约层） | Tools 分 client/server | **主参照** |
| G5 OpenHands | 中 | Session、Workspace | step 单轮清晰 | Policy/Tools 外置 |

---

## 6. wannian：按同一逻辑落位

```text
【一个循环】
  AgentLoop.run(AgentInput, AgentBudget) → AgentOutcome
  只做：decide ↔（K02 起）tools.execute ↔ 回灌；预算/取消检查；打 Trace

【其他模块】
  ModelPort              Model
  ToolRuntime            Tools（K02）
  AgentBudget            Budget
  ContextAssembler       Context（策略可 OWNER: USER）
  TurnEngine+Committer   Session / Persist
  cancelToken + CAS      Cancel（与 COMMITTING 竞态在引擎）
  AgentTrace             Events（脱敏）
  ToolPolicy/Background  Policy（后置）
  /chat/ · Channel SPI   Channel
  （不做）Handoff        Multi-Agent
```

与 [05 工作簿](../guide/05-agent-loop.md) 一致：**Loop 不写库、不发完成事件、不改原始 Message**——那些都是「其他模块」。

本项目要建的模块与批次见专文：[wannian-loop-modules.md](./wannian-loop-modules.md)。

---

## 7. 结论

1. **本质只有一个循环**；产品差异 = 模块怎么切、切完胖不胖。  
2. 梳理任意 Agent 时，先圈出 while/图环，其余一律归入 §1.3 模块表。  
3. wannian K01：循环对齐 **G4**；Outcome 对齐 **G1**；边界对齐 **L2 核壳**；治理细节按需从 **L1** 摘到 Policy 模块。  
4. 评审口令：*「这是循环，还是模块？」* —— 答不上来就不要写进 `DefaultAgentLoop`。

证据路径与远程 URL 见上一版同文 §6（文件未删减意图：本修订聚焦抽象；路径仍有效）：

- 本机：han-agent `ReactAgentLoop`；openclaw `agent-loop.ts`；hermes `conversation_loop.py`；dsh `agent.ts`；CowAgent `agent_stream.py`
- 远程：openai-agents `run.py`；langgraph `chat_agent_executor.py`；smolagents `agents.py`；Anthropic tool-use 文档；OpenHands `LocalConversation.run`
