# Agent Run / Session Logging · 同业调研

`date`: **2026-09-24**  
`purpose`: 为 **wannian-agent 0.2.3-L**（行为/debug 日志）提供可落地的同业证据；对齐后续 **0.2.4-A `turn_step` 统一行为账本**（[`k04-behavior-journal.md`](../plans/k04-behavior-journal.md)），但不替代 ErrorCodes / SafeErrorLog 运维通道。

**用户诉求（本调研输入）：** 每次模型调用的完整内容、每次工具调用的完整细节、进程启动/关闭事件；可查询、可复盘，与运维错误日志分离。

---

## 1. 同业概览（按目标）

### 1.1 OpenAI Agents SDK（Python）

| 维度 | 内容 |
|------|------|
| **记什么** | 分层 **Trace → Span**：`Runner.run` 整段为 trace；内含 `task_span`、`turn_span`、`agent_span`、`generation_span`（LLM input/output/model/model_config/usage）、`function_span`（tool name/input/output）、`guardrail_span`、`handoff_span`、音频 span 等。 |
| **存哪** | 默认 **内存批处理 → OpenAI Traces 云端**（`BatchTraceProcessor` + `BackendSpanExporter`）；可 `add_trace_processor()` 自定义落地。非 OpenAI 模型也可用 tracing API key 导出到同一 dashboard。 |
| **脱敏** | `RunConfig.trace_include_sensitive_data`（默认 **True**）：False 时 `generation_span` / `function_span` **省略 prompt 与 tool I/O**；Responses API 仍保留 `response_id` 作关联。音频有独立 `trace_include_sensitive_audio_data`。可用自定义 processor 在 export 前 redact。 |
| **生命周期** | trace 需 `start/finish`；长驻 worker 建议 `flush_traces()` 保证及时导出。 |

**来源：** [docs/tracing.md](https://github.com/openai/openai-agents-python/blob/main/docs/tracing.md) · [span_data.py](https://github.com/openai/openai-agents-python/blob/main/src/agents/tracing/span_data.py) · [官方 Tracing 页](https://openai.github.io/openai-agents-python/tracing/)

**对 wannian 的启示：** 模型/tool 明细与「可关掉的敏感字段」分开关；trace 是 **观测/export 管道**，不是业务真源——与本仓「账本 ≠ AgentTrace 内存投影」一致。

---

### 1.2 DeepSeek Harness / Cordis（dsh-session）

| 维度 | 内容 |
|------|------|
| **记什么** | **Append-only `SessionEvent` 日志 = 唯一真源**；LLM 消息历史由 `deriveMessages()` 投影，不另存 transcript。核心事件：`turn/start` · `turn/end` · `step/start` · `step/end` · `user/message` · `assistant/chunk`（流式 token）· `assistant/message`（含 usage）· **`tool/call`（原始 arguments JSON 字符串）** · **`tool/result`（完整 ToolResultMessage + 可选 error/meta）** · `request/header`（model、system prompt、tool schemas）· `request/context`（路由容量）。 |
| **存哪** | 内存 Session + **persistence seam** 可插后端；事件 **lossless JSON**，seq 单调。Cordis 框架把 session、loop、tools 都做成可替换 plugin（[`docs/architecture.md`](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md)）。 |
| **脱敏** | 核心设计强调 **verbatim 可重放**；脱敏在 tool/adapter 或展示层，而非 session 事件本身省略字段（文档未描述内置 redaction）。 |
| **生命周期** | turn/step 边界清晰；`turn/end` 带 `TurnEndReason`；checkpoint 策略与 turn 边界解耦。 |

**来源：** [docs/subsystems/session.md](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/session.md) · [packages/core/session/README.md](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/session/README.md) · [Agent Loop 深度文](https://dsh-in-depth.com/core/agent-loop)

**对 wannian 的启示：** **`turn_step` + `request_json`/`result_json` 的形态最接近 dsh 的 step + tool/call + assistant/message**；若用户要「每次模型/工具全量」，应把 **MODEL_CALL / TOOL_CALL 一步一行、同事务边界** 作为目标，而非 log.info。

---

### 1.3 OpenClaw

| 维度 | 内容 |
|------|------|
| **记什么（三层）** | ① **Audit ledger**（默认开）：`agent_run`、`tool_action`；可选 `message`（默认关）。仅 **metadata**（agent/session/run id、tool 名、status、时间），**不含 prompt、message body、tool args/result**。② **Gateway 文件日志**：JSONL（`/tmp/openclaw/openclaw-YYYY-MM-DD.log`），运维/debug。③ **Session transcript**：每 agent 的 `openclaw-agent.sqlite` 存会话与 transcript 正文（与 audit 分工）。 |
| **存哪** | Audit → **共享 state DB**（30 天 TTL，10 万行 cap）；日志 → 按日文件；transcript → 每 agent SQLite。 |
| **脱敏** | Audit 固定 `redaction: "metadata_only"`。文件日志与 transcript 支持 `logging.redactPatterns`；内置 form-body / auth-header / AWS key 保护。Tool payload 另有 redaction 合并默认规则。 |

**来源：** [CLI audit](https://docs.openclaw.ai/cli/audit) · [config observability](https://docs.openclaw.ai/gateway/config-observability) · [gateway logging](https://docs.openclaw.ai/gateway/logging) · [FAQ 路径](https://docs.openclaw.ai/help/faq)

**对 wannian 的启示：** **审计索引 vs 行为真源 vs 运维日志** 三分法；本仓已有 ErrorCodes/SafeErrorLog ≈ OpenClaw 文件日志+错误；**0.2.3-L 用户要的「全量模型/工具内容」应对标 transcript/DSH session，不是 metadata audit**。

---

### 1.4 Hermes Agent

| 维度 | 内容 |
|------|------|
| **记什么** | **`~/.hermes/state.db`（SQLite WAL）**：`sessions` 元数据 + **`messages` 全量**（role、content、tool_calls JSON、tool_name、token_count 等）。FTS5 可搜 content/tool。Gateway hooks 有 `session:start`、`agent:step`、`agent:end` 等 **进程内事件**，但 **无独立 action audit 表**（[#1155](https://github.com/NousResearch/hermes-agent/issues/1155) 提议后倾向「查 state.db + 插件 hook」）。 |
| **存哪** | 单库 per profile；历史从 JSONL 迁到 SQLite。 |
| **脱敏** | 对话级存储，文档强调 persistence 失败不挡回复；专用 audit/redact 需插件（如 waxseal 提案：hash-chained JSONL + 写前 redact secrets）。 |

**来源：** [Session Storage 官方文档](https://hermes-agent.nousresearch.com/docs/developer-guide/session-storage) · [hermes_state.py](https://github.com/NousResearch/hermes-agent/blob/main/hermes_state.py)

**对 wannian 的启示：** 「消息表存全量 + 元数据索引」可行，但 wannian 已有 **Message/Turn 表**；0.2.3-L 更适合 **逐步骤 `turn_step` 账本** 补 Loop 内 MODEL/TOOL 明细，避免与 Message 双写语义冲突。

---

### 1.5 Mem0

| 维度 | 内容 |
|------|------|
| **记什么** | **记忆层，非 run audit**。`add(messages, run_id=..., infer=False)` 可 **无损 append 会话事件**；`infer=True` 时 LLM 抽 fact。Event-based 模式：先 append 原始事件，后台 compaction 成长期记忆（[博客](https://mem0.ai/blog/event-based-memory-systems-for-long-running-ai-agents)）。 |
| **存哪** | 向量库 + 元数据（user/agent/run scope）；自托管或 SaaS。 |
| **脱敏** | 记忆策略与 scope 分离；非调试日志产品。 |

**来源：** [mem0/memory/main.py](https://github.com/mem0ai/mem0/blob/main/mem0/memory/main.py) · [LLM.md](https://github.com/mem0ai/mem0/blob/main/LLM.md)

**对 wannian 的启示：** `run_id` 作用域与 **append-only 事件** 模式可借鉴；Mem0 解决「记住什么」，不解决「debug 每一步」——本仓 Memory（0.2.3）与行为账本（0.2.4-A）应继续分工。

---

### 1.6 CrewAI

| 维度 | 内容 |
|------|------|
| **记什么** | **Event bus** 发射 typed events（agent/task/LLM/tool/A2A/…）；`RuntimeState` **记录 emitted events** 供 checkpoint/resume。Checkpoint 可绑 `llm_call_completed`、`task_completed` 等。内置 **Tracing** → CrewAI AMP 云端（decision timeline、tool、LLM prompts/responses）。 |
| **存哪** | Checkpoint → **JSON 目录或 SQLite**（`CheckpointConfig`）；trace → 云端；event 记录在内存 RuntimeState，可 snapshot。 |
| **脱敏** | Checkpoint 存完整 RuntimeState（含 event history）；云端 trace 细节取决于 AMP 策略（文档未强调本地 redaction）。 |

**来源：** [Checkpointing 文档](https://docs.crewai.com/edge/en/concepts/checkpointing) · [event_bus.py](https://github.com/crewAIInc/crewAI/blob/main/lib/crewai/src/crewai/events/event_bus.py) · [Tracing](https://docs.crewai.com/edge/en/observability/tracing) · [PR #5241 RuntimeState](https://github.com/crewAIInc/crewAI/pull/5241)

**对 wannian 的启示：** **事件类型枚举 + 单调序号 + 可选 checkpoint** 与 `turn_step.step_no` 同构；LLM 完成事件粒度适合 debug，但 wannian 应 **SQLite 同事务** 而非仅 JSON 快照。

---

### 1.7 Claude Code / Cursor Agent Transcripts（高层）

| 维度 | 内容 |
|------|------|
| **记什么** | **Claude Code 磁盘 transcript**：`~/.claude/projects/<encoded-cwd>/<sessionId>.jsonl`，每行 JSON；`user`/`assistant`/`system` 等；assistant 含 `text` / `thinking` / **`tool_use`**；user 行可含 **`tool_result`**。CLI **`stream-json`** 另有一套 NDJSON 事件流：`system/init`、`assistant`、`user`（tool_result）、`stream_event`（delta）、`result`。 |
| **Cursor** | 项目下 **`agent-transcripts/<uuid>.jsonl`**：每行 `{"role":"user|assistant","message":{"content":[...]}}`；assistant content 块含 `tool_use`（name + input）；**不替代** Cursor 产品内部 telemetry。 |
| **存哪** | 本地 JSONL（append-only）；subagent 独立 `subagents/agent-*.jsonl`。 |
| **脱敏** | 默认 **全量本地**；企业/合规需外部策略。stream-json 用于自动化解析（[supabase evals parser](https://github.com/supabase/evals/blob/main/packages/core/src/agents/claude-code/parser.ts)）。 |

**来源：** [Claude session schema 调研页](https://lin-guanguo.github.io/llm-memory-research/agent-cli/claude-session-file-schema/) · [cclens session-format spec](https://docs.rs/crate/cclens/latest/source/docs/specs/session-format.md) · [lobehub claudeCode adapter](https://github.com/lobehub/lobehub/blob/main/packages/heterogeneous-agents/src/adapters/claudeCode.ts)

**对 wannian 的启示：** IDE 类产品的 debug 默认 **本地全量 transcript**；服务端 wannian 应对标 **DSH session 事件 + SQLite**，而非仅 IDE JSONL。

---

### 1.8 Spring Boot / Quarkus 系 Agent 产品

#### Spring AI Session（社区）

| 维度 | 内容 |
|------|------|
| **记什么** | **`SessionEvent`** 包装 Spring AI `Message`；append-only **`AI_SESSION` + `AI_SESSION_EVENT`**（`seq` 单调）；compaction 归档不删历史。 |
| **存哪** | JDBC（PostgreSQL / MySQL / **H2** 等）；可换 Redis。 |
| **脱敏** | 文档未规定；由应用层 Message 内容决定。 |

**来源：** [spring-ai-session 文档](https://spring-ai-community.github.io/spring-ai-session/latest/) · [session-jdbc schema](https://spring-ai-community.github.io/spring-ai-session/latest/session-jdbc/)

#### NewWaveAI spring-agent

| 维度 | 内容 |
|------|------|
| **记什么** | `agent.stream()` 发射 **`AgentEvent`**：`tool_execution_start`（**toolUse.name + input**）、`tool_execution_end`（**result**）、`message_update`、`thinking_update`、`agent_end`（usage）。可选 **Timeline** 镜像落 JDBC。 |
| **存哪** | 内存 Flux + JDBC conversation/timeline store。 |
| **脱敏** | 未强调；事件 JSON 直出 SSE。 |

**来源：** [spring-agent README](https://github.com/NewWaveAI/spring-agent) · [END_TO_END.md](https://github.com/NewWaveAI/spring-agent/blob/main/docs/END_TO_END.md)

#### enrichmeai ai-coding-agent

| 维度 | 内容 |
|------|------|
| **记什么** | SQLite **`audit_events`**：**每次 LLM call + tool invocation**；`GET /api/sessions/{id}/audit` 只读；MDC 带 requestId/userId/sessionId。 |
| **存哪** | `AGENT_STORAGE_TYPE=sqlite` 时 JPA 持久化。 |
| **脱敏** | 依赖 auth 归因；无 auth 时 actor=anonymous。 |

**来源：** [README](https://github.com/enrichmeai/ai-coding-agent)

#### Quarkus LangChain4j

| 维度 | 内容 |
|------|------|
| **记什么** | **OpenTelemetry spans**（`langchain4j.aiservices.*`、`langchain4j.tools.*`）；CDI 事件 `AiServiceRequestIssuedEvent`（**system + user message**）、`AiServiceResponseReceivedEvent`、`ToolExecutedEvent`（**tool request + result**）；agentic 扩展计划 `AgentStartedEvent` / `AgentCompletedEvent` / metrics。 |
| **存哪** | **OTLP 导出**（Tempo/MLflow 等），非默认 SQLite 行为账本。 |
| **脱敏** | 取决于 OTel collector / 后端策略。 |

**来源：** [Quarkus observability](https://docs.quarkiverse.io/quarkus-langchain4j/dev/observability.html) · [LangChain4j observability tutorial](https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/observability.md) · [agentic observability issue #2548](https://github.com/quarkiverse/quarkus-langchain4j/issues/2548)

**对 wannian 的启示：** Java 栈同业 **spring-agent / enrichme** 最接近「LLM+tool 结构化 audit 落 SQLite」；Quarkus 路线偏 **可观测 export**，适合未来接 OTLP，但 **不满足用户要的本地可查询全量账本**（除非自建 span exporter 写库）。

---

## 2. 对比表

| 同业 | 主要事件/span 种类 | 持久化 | Prompt / 模型输出 | Tool 名 | Tool args | Tool result | 进程/服务 lifecycle |
|------|-------------------|--------|-------------------|---------|-----------|-------------|----------------------|
| OpenAI Agents SDK | trace, task, turn, generation, function, … | 云端 trace（默认）；可自定义 processor | 默认 **全量**（可关） | ✓ | 默认 **全量**（可关） | 默认 **全量**（可关） | 无一等进程事件；靠 custom span |
| DSH / Cordis | turn/*, step/*, assistant/*, tool/*, request/* | Append-only session log → 可插 DB | **全量**（含 chunk + assembled message） | ✓ | **原始 JSON 字符串** | **完整 message** | session/end-seed 等；无 JVM 级 |
| OpenClaw | audit: agent_run, tool_action; transcript 另库 | State DB + agent SQLite + JSONL 日志 | Audit **无**；transcript **有** | Audit ✓ | Audit **无** | Audit **无** | Gateway 日志含启动配置；audit 无 body |
| Hermes | messages 行 + hooks | SQLite state.db | **全量** content | ✓ | tool_calls JSON | tool role messages | hooks: session:start 等 |
| Mem0 | memory ADD/UPDATE 事件 | 向量+元数据 | infer=False 时 **全量** | — | metadata | — | — |
| CrewAI | 50+ event types + trace | Checkpoint JSON/SQLite + 云 trace | Trace **有**；checkpoint 含 state | ✓ | 事件载荷内 | 事件载荷内 | crew_kickoff_* 等 |
| Claude Code / Cursor | JSONL user/assistant/system/tool_* | 本地 JSONL | **全量** | ✓ | **全量** input | **全量** result | system/init 等 |
| Spring AI Session | SessionEvent(message) | JDBC AI_SESSION_EVENT | Message **全量** | 在 message 内 | 在 message 内 | 在 message 内 | 无 |
| spring-agent | AgentEvent 流 | JDBC 可选 | stream delta + end | ✓ | **start 含 input** | **end 含 result** | 应用级 |
| enrichme agent | audit_events | SQLite | **有**（LLM 行） | ✓ | **有** | **有** | 结构化 logback + MDC |
| Quarkus LC4j | OTel span + CDI events | OTLP 后端 | span/event **有** | ✓ | ToolExecutedEvent | ToolExecutedEvent | Quarkus 标准 logging |

---

## 3. 模式归纳

1. **事件溯源账本（DSH、Spring AI Session、CrewAI checkpoint）** — append-only、seq/step 单调、可投影出对话；最适合 wannian **`turn_step`** 已定方向。  
2. **全量 transcript（Claude/Cursor/Hermes messages）** — debug 友好，易与「用户可见 Message 表」混淆；wannian 应用 **`turn_step` 存 Loop 内步骤明细**，Message 仍表用户/助手成品。  
3. **Metadata audit（OpenClaw audit ledger）** — 跨 run 检索、合规索引；**不能**满足「每次模型调用具体内容」诉求。  
4. **Trace/OTel（OpenAI SDK、Quarkus）** — 开发/生产观测；默认可关敏感字段；通常 **不是业务 commit 路径**。  
5. **运维错误通道（OpenClaw JSONL、wannian ErrorCodes/SafeErrorLog）** — 失败、超时、栈；**≠ 行为账本**（k04 已钉死）。

---

## 4. 对 wannian 0.2.3-L 的建议

### 4.1 与现有设计对齐

| 已有/计划 | 本阶段用法 |
|-----------|------------|
| [`k04-behavior-journal.md`](../plans/k04-behavior-journal.md) **`turn_step`** | 0.2.3-L **提前落地核心形状**（表 + 单一写入路径），避免 log.info 冒充审计；字段对齐 [`04-kernel-reference.md` §turn_step](../guide/04-kernel-reference.md)：`turn_id`, `step_no`, `kind`, `request_json`, `result_json`, `status`, `started_at`, `finished_at`。 |
| **ErrorCodes / SafeErrorLog** | **禁止**把运维错误当 `turn_step`；失败 Turn 可在 step 上记 `error_code` **引用** ErrorCodes，不复制 SafeErrorLog 栈文本。 |
| **AgentTrace** | 仅内存投影/UI；**不得**成唯一真相源（k04 验收项）。 |
| **Message / Turn** | 继续表「提交后的对话事实」；`turn_step` 表「如何走到那里」（含中间 MODEL/TOOL）。 |

### 4.2 建议的 `kind` 扩展（0.2.3-L）

在 k04 草案（`USER_INPUT`, `MODEL_CALL`, `TOOL_CALL`, `MEMORY_WRITE`, `FINALIZE`）上，为 **用户明确诉求** 增加：

| kind | actor | request_json / result_json 建议载荷 | 对标 |
|------|-------|-------------------------------------|------|
| `PROCESS_START` | `system` | 版本号、profile、DB 路径摘要、关键配置快照（**无密钥**） | 运维启动日志 + OpenClaw gateway 启动行 |
| `PROCESS_SHUTDOWN` | `system` | 原因（SIGTERM/正常退出）、drain 状态 | 同上 |
| `USER_INPUT` | `user` | 入站消息 id / 摘要 | DSH `user/message` |
| `MODEL_CALL` | `agent` | **request**：assembled messages 摘要或全量（配置项）；model id；**result**：assistant 输出 + usage + finish_reason | DSH `assistant/message` + OpenAI `generation_span` |
| `TOOL_CALL` | `agent` | **request**：tool 名 + **完整 arguments**；**result**：工具结果 + operationId（若有） | DSH `tool/call` + `tool/result` |
| `FINALIZE` | `agent` | turn 终态、总 step 数 | DSH `turn/end` |

**MEMORY_WRITE** 可在 0.2.3 Memory 已交付后按 k04 再补；0.2.3-L 优先 MODEL/TOOL/进程生命周期。

### 4.3 明细程度（回应「每次模型/工具都要详细」）

- **默认（debug 模式 / 内网部署）：** `MODEL_CALL` / `TOOL_CALL` 的 `request_json`/`result_json` 存 **脱敏后全量**（与 k04「禁止密钥、Authorization、完整敏感工具结果、隐藏推理」一致——**隐藏推理**指 chain-of-thought 不进库，不是省略 tool args）。  
- **对标 DSH：** tool arguments 保留 **模型原始 JSON 字符串** 便于复盘解析失败。  
- **配置开关（建议）：** `wannian.journal.include-sensitive=false` 时降级为摘要 + digest（对标 OpenAI `trace_include_sensitive_data=false`），**生产默认可 false，开发默认可 true**。

### 4.4 写入路径

1. **Turn 内步骤：** 仅在 **TurnEngine / TurnCommitter 事务边界** 追加 `turn_step`（与 k04「一条写入路径」一致）；MODEL 在 `ModelPort` 返回后、TOOL 在 `ToolRuntime` 完成后各一行。  
2. **进程起停：** Spring `@EventListener` `ApplicationReadyEvent` / shutdown hook → `actor=system` 的 `PROCESS_*` 行；**无 turn_id** 时可使用 sentinel turn_id=`__system__` 或 nullable FK + 独立 `conversation_id`（实施时二选一，需在 migration 说明）。  
3. **禁止：** 各模块独立 `log.info` 写 JSON 冒充账本；SSE/outbox **不**先于 commit 暴露未完成 step。

### 4.5 查询与验收（0.2.3-L 最小）

- 仓储：`listStepsByTurnId(turn_id)` + `listSystemSteps(since)`。  
- 断言：一次含 tool 的 Turn → step_no 单调且含 ≥1 `MODEL_CALL` + ≥1 `TOOL_CALL` + `FINALIZE`；重启进程 → 存在 `PROCESS_START`。  
- 脱敏测试：含 `Authorization:` 的 tool result 不得原样出现在 `result_json`。

### 4.6 与 0.2.4-A 的关系

0.2.3-L 可视为 **k04-A 的窄版提前交付**（MODEL/TOOL/进程 + SQLite + 单写路径 + 脱敏），**不**阻塞后续补 `MEMORY_WRITE`、Outbox 暴露策略、管理 UI。若 migration 编号暂用 V0xx 占位，合并 0.2.4-A 时保持 **`turn_step` 列兼容**，避免二次迁移。

---

## 5. 参考链接（Primary）

| 主题 | URL |
|------|-----|
| OpenAI Agents tracing | https://github.com/openai/openai-agents-python/blob/main/docs/tracing.md |
| OpenAI span payloads | https://github.com/openai/openai-agents-python/blob/main/src/agents/tracing/span_data.py |
| DSH session events | https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/session.md |
| DSH architecture | https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md |
| OpenClaw audit | https://docs.openclaw.ai/cli/audit |
| OpenClaw observability config | https://docs.openclaw.ai/gateway/config-observability |
| Hermes session storage | https://hermes-agent.nousresearch.com/docs/developer-guide/session-storage |
| Mem0 add/run_id | https://github.com/mem0ai/mem0/blob/main/mem0/memory/main.py |
| CrewAI checkpointing | https://docs.crewai.com/edge/en/concepts/checkpointing |
| CrewAI event bus | https://github.com/crewAIInc/crewAI/blob/main/lib/crewai/src/crewai/events/event_bus.py |
| Claude session JSONL | https://lin-guanguo.github.io/llm-memory-research/agent-cli/claude-session-file-schema/ |
| Spring AI Session JDBC | https://spring-ai-community.github.io/spring-ai-session/latest/session-jdbc/ |
| spring-agent events | https://github.com/NewWaveAI/spring-agent/blob/main/docs/END_TO_END.md |
| enrichme audit | https://github.com/enrichmeai/ai-coding-agent |
| Quarkus LangChain4j observability | https://docs.quarkiverse.io/quarkus-langchain4j/dev/observability.html |
| wannian k04 行为账本 | [`../plans/k04-behavior-journal.md`](../plans/k04-behavior-journal.md) |
| wannian turn_step  schema | [`../guide/04-kernel-reference.md`](../guide/04-kernel-reference.md) |

---

## 6. 待决（实施前需产品/astra 拍板）

1. **`turn_id` 为空时的系统事件**：sentinel turn vs nullable FK。  
2. **`MODEL_CALL` 是否存 system prompt 全量**：DSH 存 `request/header`；wannian 可能很大——建议存 hash + 可选全量（配置）。  
3. **隐藏推理**：assistant 若含 reasoning 字段，是否整段剔除还是单独 `kind=MODEL_REASONING` 且默认不导出 SSE。  
4. **保留策略**：SQLite 无限增长 vs 按 conversation 归档（同业 OpenClaw audit 30 天 cap 仅适用于 metadata 索引，不适用于全量账本）。
