# 0.2.3 · 记忆管线分层与方法菜单（待逐层选定）

`status`: **selections-complete + R1** — 2026-09-23；§4 已齐；§4.1 覆盖 S8/S10/S12（importance / 衰减 / 弱 B / claim 规范化）  
`purpose`: 分层 S0–S12；**每选项写明对标哪个 Agent 的哪个模块**（抄什么 / 不抄什么）；无内容启发式与正则抽记；你逐层选定后再写施工单。  
`code`: **0.2.3 已按 [k03-memory.md](../plans/k03-memory.md) R1 交付**；旧半成品不得复活。  
`authority`: [memory.md](./memory.md) · [06](../guide/06-memory.md) · [业界调研](../../../../docs/architecture/research-2026-09-17-agent-memory-patterns.md) · k03 §0.1

**归因约定：** 「对标」= 行为/模块形状来自该系统；「本仓改造」= 钉死不照搬的部分（尤其写库缝）。

---

## 0. 硬约束

| # | 不变量 |
|---|--------|
| 1 | 无内容启发式 / 无正则作「抽什么 / 什么 kind」生产实现 |
| 2 | S0 回话不直接写 memory/rel 表 |
| 3 | 正式变更只经 TurnCommitter 或批准的短事务 Command |
| 4 | 近讯 / 长期记忆 / 关系三通道；压缩 ≠ 真源 |
| 5 | 敏感不落库；冲突不静默盖；可纠正 / 可遗忘 |

---

## 0.1 批判维度（每层选项必评 · 含 2C2G）

部署语境（产品已钉）：**wn-server = Java 21 + Spring Boot**；资源验收目标见清单 **0.2.7** / 测试工作簿：**2 核 · 2 GB** 等价限制下的 RSS/峰值。对标 Agent 多为 **Python**（Hermes、OpenClaw、Mem0 SDK、LangMem）。「抄方案」≠「抄进程」。

| 维度 | 问什么 | 本仓及格线 |
|------|--------|------------|
| **语言** | 对标实现是什么语言？我们是 **移植语义到 Java**，还是要嵌 Python/Node 子进程？ | 默认 **只允许 Java 内核实现**；禁止为记忆再拉常驻 Python 服务（2G 吃不起双运行时） |
| **框架适配** | 能否挂进现有 `TurnEngine` / `ToolRuntime` / `TurnCommitter` / `ContextAssembler` / SQLite Flyway？是否要 Hermes 式 Provider SPI 或 OpenClaw 式 md 工作区真源？ | 优先 **Port + 现有 Turn 事务**；不引入第二套记忆宿主 |
| **性能 · 2C2G** | 每 Turn 额外 LLM 次数、同步阻塞、堆/线程/队列、向量索引、后台 Job 争用 | 见下表预算；超预算标 **红灯**，须降级或更后 |

### 2C2G 性能预算（记忆子系统 · 选型用）

| 预算项 | 建议上限（0.2.3 选型） | 说明 |
|--------|------------------------|------|
| 每 Turn **额外**记忆 LLM 调用 | 默认 **0～1**；峰值忌 ≥2 与回话抢同一小模型/API 配额 | 2C 上本地小模型双推理易抖；云 API 则双重计费与尾延迟 |
| 记忆路径同步阻塞用户可见回复 | **禁止**（或仅 tool 已在 Loop 内的那次） | 对齐 Hermes/OpenClaw「失败不挡回复」 |
| 常驻额外进程 | **0**（无 Mem0 服务、无 Python plugin 宿主） | 2G 需留给 JVM + SQLite + 页面 |
| 向量索引 / 大嵌入模型 | **本批不做** | 内存与磁盘双杀 |
| 后台 Job 与聊天并发 | 本批慎开；更后也要限并发=1 | 2C 上 Job+聊天易互相饿死 |
| 召回 | SQLite 过滤 + 小预算 Top-N；忌全表进堆再排序超大结果 | |

### 评分记号（后文批判栏）

| 记号 | 含义 |
|------|------|
| ● 绿 | 语言可 Java 落地；贴合现框架；2C2G 可承受 |
| ▲ 黄 | 能做但有成本（多一次调用 / 双路径去重 / 异步与事务别扭） |
| ■ 红 | 语言或框架硬拧，或 2C2G 明显吃紧 → 更后或不选 |

### 选项正文固定栏目（在原有对标之上叠加三维）

每一选项必须同时具备：

1. **对标 / 模块 / 抄 / 不抄 / 本仓落点 / 优点 / 缺点 / 本批**（原讨论）  
2. **语言 · 框架适配 · 2C2G 性能**（本轮追加的批判维）

二者缺一不可；三维**不是**用来替换对标说明的。

---

## 1. 分层总览

```text
Turn 开始:  S8 召回 → S9 关系读 → S10 装配 → S0 回话
Turn 完成:  S1 触发 → S2 抽记 → S3 定轴 → S4 裁决 → S5 关系草案 → S6 提交 → S7 存储
会话外:    S11 纠正/遗忘 · S12 后台整理
```

---

## 1.1 Hermes / OpenClaw 整方案对照（按本仓分层）

二者都是**本地可核验**的完整宿主，不是「单点 API」。下面按 S0–S12 拆开；**不等于**推荐整包照抄。

### Hermes =「宿主 SPI + 可插拔 Provider」

**核心模块**

| 模块 | 路径 | 职责 |
|------|------|------|
| `MemoryManager` | `hermes-agent/agent/memory_manager.py` | 唯一编排点：prefetch / sync / tool schema / 关闭 drain |
| `MemoryProvider` | `hermes-agent/agent/memory_provider.py` | 抽象：`prefetch`、`sync_turn`、`get_tool_schemas`、`handle_tool_call`、可选 `on_session_end` / `on_pre_compress` |
| 插件后端 | `hermes-agent/plugins/memory/*` | Mem0、Honcho、Hindsight、OpenViking、Supermemory…（**同时只允许一个外部 Provider**） |
| 内置文件记忆 tool | `hermes-agent/tools/memory_tool.py` | `MEMORY.md` / `USER.md`；tool action：`add` / `replace` / `remove`；会话开始注入冻结快照 |

**运行时（对标我们的层）**

```text
Turn 前:  MemoryManager.prefetch_all(user)     ≈ S8 召回（内容由 Provider 定）
回话中:  Provider.get_tool_schemas() 暴露工具   ≈ S0-b（可有记忆 tool）
         + 内置 memory_tool 写 MEMORY.md/USER.md ≈ S0-b + 文件 Store
Turn 后: sync_turn 进单线程队列，不挡回复       ≈ S1-d
压缩前:  on_pre_compress checkpoint              ≈ 与近讯压缩钩子，≠ 长期 Policy
关闭时:  drain 限时                              ≈ 失败不挡进程退出
```

| 本仓层 | Hermes 实际怎么做 |
|--------|-------------------|
| **S0** | **偏 S0-b**：模型可走 Provider 工具 + 内置 `memory` tool |
| **S1** | **偏 S1-d**：回复后异步 `sync_turn`；另有 session-end / pre-compress 钩子（视 Provider） |
| **S2/S3/S4** | **不在 Hermes 内核统一**：Mem0 Provider 可能每轮同步终稿；Hindsight 提炼 observation；Supermemory 会话结束 ingest… **换插件 = 换产品语义** |
| **S6** | 无本仓式 Freeze+同事务；后台队列写，与 chat 落库分离 |
| **S7** | **偏 S7-c**：外挂 Provider；另有本地 md 文件（类似轻量 MemFS） |
| **S8** | Turn 前 `prefetch`；失败/超时不挡回话 |
| **S11** | 视 Provider（如 forget）；内置 tool 可 replace/remove 文件条目 |
| **S12** | Provider 自带（无统一 dreaming） |

**对本仓启示：** 可抄 **编排时机**（先回后 sync、prefetch、drain、只挂一个外部后端）。**不可**把「记什么 / 冲突 / 敏感」外包给 Provider——必须钉死在 S2–S4 / Committer。

---

### OpenClaw =「分层文件 + 热路径工具 + 后台 Dreaming 晋升」

**核心模块 / 文档**

| 模块 | 路径 | 职责 |
|------|------|------|
| 概念总览 | `openclaw/docs/concepts/memory.md` | `USER.md` / `MEMORY.md` / 日更 `memory/YYYY-MM-DD.md` / `DREAMS.md` |
| 架构 | `openclaw/docs/concepts/memory-architecture.md` | 分层信任、写时 provenance、确定性门 + 门内模型判断 |
| Dreaming | `openclaw/docs/concepts/dreaming.md` + plugin `memory-core` | Light→REM→Deep；Deep 才写 `MEMORY.md` |
| Provenance | `openclaw/docs/concepts/memory-provenance.md` | origin：owner / agent / untrusted / system |
| CLI | `openclaw memory forget` 等 | dry-run、session 禁再摄取 |

**分层存储（OpenClaw 自己的「通道」）**

| Tier | 表面 | 谁写 | 是否每轮注入 |
|------|------|------|--------------|
| Instructions | `AGENTS.md` 等 | 人 | 会话开始 |
| Curated core | `MEMORY.md`、`USER.md` | Dreaming 巩固；或用户明示记住 | 会话开始（有预算/provenance） |
| Episodic | 日更 md、session transcript | Agent 工作中；flush；转录 | **不**自动注入；`memory_search` / `memory_get` |
| Prospective | standing intents / cron | intent tool | 触发时 |
| Review | `DREAMS.md` | dreaming | 给人看，不注入 |

**运行时**

```text
热路径:  召回 curated + 可 search  episodic；失败不挡回复
回话中:  「记住…」→ 写入合适文件 / 工具          ≈ S0-b
         工作中可写日更（episodic），≠ 直接污染 MEMORY.md
后台:    Dreaming Light 暂存候选
         → REM 主题强化
         → Deep：确定性评分门槛（minScore / minRecallCount / minUniqueQueries）
           → 排除 untrusted/system
           → 通过后 consolidation 模型改写 MEMORY.md
遗忘:    forget dry-run；正式后 session 标记 forgotten，防再自动长回
```

| 本仓层 | OpenClaw 实际怎么做 |
|--------|---------------------|
| **S0** | **偏 S0-b**：可明示「Remember that…」写文件；另有 search/get 类工具 |
| **S1** | **双轨**：热路径可写 episodic；**长期晋升在 S12（Dreaming）**，不在每 token / 不阻塞回复 |
| **S2** | 热路径：模型/日更写入；Deep：过门候选再交给 consolidation 完成 |
| **S3/S4** | **强**：写时 provenance；Deep 前 **确定性评分门** + 来源门；门内才用模型判断 |
| **S6** | 文件 + SQLite 索引；**无**本仓 Turn 同事务 Approved* |
| **S7** | Markdown 工作区 + SQLite index（偏文件真源，不是单表 memory_record） |
| **S8** | 会话开始注入 curated；episodic 按需 search；预算截断 |
| **S11** | 可编辑 md；`memory forget`（dry-run + 禁再摄取） |
| **S12** | **整包就是 S12-b**：dreaming 三阶段 + 候选提升 |

**对本仓启示：** 强烈可借鉴 **provenance、forget 墓碑、失败不挡回复、episodic≠curated**。  
**本批不整包抄：** Deep 的 **多信号评分提升**（属启发式门控，已否决作 S3/S4 主路径）；完整 dreaming Job。

---

### 对照一眼

| | Hermes | OpenClaw | 本仓菜单里接近 |
|--|--------|----------|----------------|
| 产品形状 | SPI 宿主，语义在插件 | 自研分层文件 + dreaming | 自研领域层 + SQLite |
| 回话是否有记忆 tool | 常有（S0-b） | 常有（S0-b） | S0-a 或 S0-b 你选 |
| 抽记时机 | 异步 sync_turn（S1-d） | 热写 episodic + 后台晋升（S1+S12） | S1-a/b/c/d/e |
| 「记什么」谁定 | **Provider 各异** | 模型写文件 + Deep 评分+巩固 | 必须自选 S2/S3/S4 |
| 长期怎么进核心 | 视插件 | Dreaming Deep → `MEMORY.md` | S12-x 本批默认不做 |
| 写与聊天原子性 | 弱（队列） | 弱（文件/后台） | **S6-a 钉死更严** |

---

## 2. 逐层选项（完整对标叙述 + 三维批判）

说明：每项先写清**对标哪个 Agent、哪个模块、抄/不抄**，再叠加 **语言 / 框架适配 / 2C2G**。禁止用一张总分表代替正文。Hermes / OpenClaw 整方案见上文 §1.1。

---

### S0 · 回话（ModelPort）— **已选 S0-b**

#### S0-a · 仅回话，无记忆 tool（未选）

**对标与模块**

- **Mem0**：典型接法是应用在 turn 后调 `Memory.add(...)`（[add 文档](https://github.com/mem0ai/mem0/blob/main/docs/core-concepts/memory-operations/add.mdx)），聊天 Agent **不**暴露 memory tool。
- **CrewAI**：`memory=True` 时在**任务完成边界**由框架抽记（`Memory.remember` / crew memory），不是对话 tool catalog。
- **LangMem**：可采用「后台 manager / ReflectionExecutor」路径，热路径也可以不挂 memory tool。

**抄什么 / 不抄什么**

- 抄：「回话」与「启动抽记」分离；记忆入口在宿主 Turn 后管线（S1+）。
- 不抄：把 Mem0 当外挂常驻库；不抄 CrewAI 的 task 边界（本仓是 chat Turn）。

**本仓落点**

现有 `ModelPort` + `ToolRuntime` **不**注册 remember/forget；何时记完全由 S1 决定。

**优点 / 缺点**

职责清、易测、无「口头记住了但没调 tool」。缺点：「帮我记住」全靠 S1/S2；无显式 tool 手感。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● 无新语言栈 |
| **框架适配** | ● 零改 Tool catalog |
| **2C2G** | ● 无 tool 额外轮次 |
| **本批** | 可做（**未选**） |

#### S0-b · 回话 + remember/forget 类 tool（只出草案，不直写库）— **已锁定**

**对标与模块**

- **Open WebUI**：`backend/open_webui/tools/builtin.py` 提供 `add_memory` / `update_memory` / `delete_memory` / `search_memories`（[Memory 功能文档](https://docs.openwebui.com/features/chat-conversations/memory/)）。
- **Letta**：用户 `/remember` 明示教给 agent；agent 可编辑 **MemFS**（[memory](https://github.com/letta-ai/letta-docs-md/blob/main/configuration/memory/index.md)、[MemFS](https://github.com/letta-ai/letta-docs-md/blob/main/concepts/memfs/index.md)）。
- **HANAGENT legacy**：`MemoryToolsRegistrar` → `save_memory` / `memory_search`（工具描述要求用户明确请求记住）。
- **LangMem**：热路径 memory tools（[memory tools 指南](https://langchain-ai.github.io/langmem/guides/memory_tools/)）。
- **Hermes**：内置 `tools/memory_tool.py`（`MEMORY.md` / `USER.md`，action add/replace/remove）；Provider 也可 `get_tool_schemas()`。
- **OpenClaw**：鼓励「Remember that…」写入工作区文件；另有检索类工具（本地 `docs/concepts/memory.md`）。

**抄什么 / 不抄什么**

- 抄：模型在 Loop 中**显式**决定「现在记住/忘掉」；tool 进入对模型可见的 catalog。
- 不抄：WebUI/部分实现里 tool **直接写 DB**；Letta MemFS **直接 commit**；legacy「HITL 仅 signal」却可能已落库的松语义。
- **本仓改造**：tool → 校验后的**草案** → S4 Policy → S6 Committer 或 S11 短事务；Adapter 禁止直写表。

**本仓落点**

`ToolRuntime` 新 descriptor；Adapter 返回草案；`TurnEngine` 并入 `FreezeCommitPlan` / `CommitTurnPlan`。

**优点 / 缺点**

「记住/忘掉」可交互；可与自动抽并存。代价：多 tool 测试面；漏调会假记住；与 S1-a 并用必须去重。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● 对标多为 Python/TS，**用 Java `ToolRuntime` 重做语义**；禁止再拉 WebUI/Hermes 常驻进程 |
| **框架适配** | ● 直接挂 0.2.2 已有 `ToolRuntime` / Loop |
| **2C2G** | ▲ 有 tool 时增加 Loop 轮次与 schema token；**无第二进程**；勿再无脑叠「每轮同步 Propose」 |
| **本批** | **已选** |

---

### S1 · Trigger（何时启动抽记）— **当前请选这一层**

S0-b 已定「模型**可以**经 tool 记」。S1 定：**除了调 tool 以外，系统还要不要、在什么时机自动打开抽记管线（S2+）**。

#### S1-a · 每 Turn 完成后同步 Propose

**对标与模块**

- **Mem0**：由**应用**决定何时 `Memory.add(messages, user_id=..., infer=True)`。公开协议不强制「等流结束」，业界常见接法是 **turn / 一段对话结束后**由宿主调用（调研：Turn 完成时机由调用方决定）。
- **陪伴产品体感**：希望「这轮说过的偏好/事实，下一轮就能用」——对应同步抽记，而不是只等模型自觉调 tool。
- 对照：**CrewAI** 在任务结束后抽（边界不同，时机思想相近：边界完成 → 抽）。

**抄什么 / 不抄什么**

- 抄：Turn（或等价边界）完成后立刻跑抽记，使下轮召回有新数据。
- 不抄：嵌入 Mem0 SDK/服务当写库后端；不把「infer」等同于「已过敏感/冲突门」（门仍在 S4）。
- 失败策略：已完成的助手回复不因抽记失败回滚（对齐 Hermes/OpenClaw「记忆失败不挡回复」的精神）。

**本仓落点**

在 `TurnEngine` 完成路径同步调用 S2 Propose；与本轮 S0-b tool 草案**合并**后进 S4 → Freeze。

**优点 / 缺点**

成长强、不依赖模型调 tool。缺点：每轮成本；与 tool 双来源须在 S4 去重；拖尾延迟。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● 纯 Java 调现有 `ModelPort`（若 S2 选独立 LLM）；不引入 Python Mem0 进程 |
| **框架适配** | ▲ 必须钉进完成时序与 `FreezeCommitPlan`；自动提案 ∪ tool 草案的去重/幂等要测 |
| **2C2G** | ■/▲ 若 S2-a，则**每轮额外 ≥1 次记忆向 LLM**；2C 上与回话争用本地模型或 API 配额；小机偏贵 |
| **本批** | 可做（接受成本才选） |

#### S1-b · 仅当模型调用 remember tool

**对标与模块**

- **Open WebUI**：热路径写入主要靠模型调用 `add_memory` 等 builtin tools；后台 review 是**另一条**可选路径（默认还可关）。
- **Letta**：`/remember` ——用户明示教，agent 决定写入哪块记忆。
- **HANAGENT legacy**：`save_memory` 的 tool **描述**写明「当用户明确请求记住时」才保存稳定事实/偏好。
- **Hermes / OpenClaw**：热路径也强调模型/用户显式写入文件或 tool（长期巩固另走后台）。

**抄什么 / 不抄什么**

- 抄：本轮若**没有**记忆类 tool 调用 ⇒ **不**启动自动 Propose；只有 tool 参数进入后续 S3/S4。
- 不抄：tool 直写 DB/文件真源；把「模型说记住了」当已提交。

**依赖**

已满足：**S0-b**。

**本仓落点**

`ToolRuntime` 产出的草案列表 →（可空）S4 → 有 Approved* 才进 Freeze；无 tool 则记忆变更列表为空。

**优点 / 缺点**

实现最简、误记少、与明示记住一致；**最贴已选 S0-b**。缺点：模型漏调就不记；静默偏好/事实易丢；「成长核心」变弱。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● 无额外抽记语言栈 |
| **框架适配** | ● 只消费 S0-b 已有结果，TurnEngine 改动面最小 |
| **2C2G** | ● **无每轮定时记忆 LLM**；成本落在用户触发的 Loop 轮次（已在回话预算内） |
| **本批** | 可做；**2C2G + S0-b 首选倾向** |

#### S1-c · 每 N 个 user turn 后台 review

**对标与模块**

- **Open WebUI**（最完整对标）：`backend/open_webui/utils/memory.py`
  - 条件：assistant 最终内容非空；user-turn 计数命中 `MEMORIES_REVIEW_INTERVAL_TURNS`（默认 **10**）；
  - 行为：创建**后台** task；reviewer 读已有记忆 + 近约 16 条消息 + 终稿，产出 JSON 操作，再 `update_memories`；
  - reviewer 请求 **`stream=False`**；提示偏向持久信息、避免临时情绪、优先 replace/delete 而非重复 add。
- 思想近亲：**LangMem** `ReflectionExecutor`（先返回再 enrichment），但 WebUI 用「间隔」而非「每轮队列」。

**抄什么 / 不抄什么**

- 抄：完成可见回复后再整理；按间隔触发；reviewer 非流式；适合合并/去重。
- 不抄：本批上完整 Memory Job 表 / lease / 崩溃恢复（memory.md §4 仍未确认）。

**与 S0-b**

Tool 负责「立刻记」；review 负责「事后整理」——双轨，和 WebUI 一样。

**优点 / 缺点**

不拖每轮首包；适合整理。缺点：记住有延迟；要 Job；2C 上 Job 与聊天争用。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● 可纯 Java 实现 reviewer 调用 |
| **框架适配** | ■ 当前内核无 Job/lease；与「本轮 Freeze 同事务」要另设计写入缝 |
| **2C2G** | ▲ 平时省；触发时一次**大上下文** LLM + 后台线程与聊天抢 2C |
| **本批** | **更后**（除非你明示先做无表轻量版） |

#### S1-d · 回复后异步队列，不挡首包

**对标与模块**

- **Hermes**：`agent/memory_manager.py` — Turn 完成后 `sync_turn` / `sync_all` 放入**单工作线程队列**，按 Turn 顺序写，**不阻塞**用户回复；进程退出 `drain` 限时（调研 §3.13）。
- **LangMem**：`ReflectionExecutor` — agent **先返回**响应，再安排 enrichment；可设延迟去抖（[API](https://langchain-ai.github.io/langmem/reference/memory/)）。
- **OpenClaw** 原则：记忆路径失败有超时/降级，**从不吃掉一轮回复**（`docs/concepts/memory-architecture.md`）。

**抄什么 / 不抄什么**

- 抄：聊天可见延迟与抽记解耦；串行队列避免打爆；关闭限时 drain。
- 不抄：把「记什么」交给 Hermes **Provider 插件**；不让异步写绕过 Freeze（除非另钉短事务模型）。

**本仓落点难点**

若坚持「Approved* 进**本轮** Freeze 同事务」，异步抽完往往只能：短事务另写，或并入**下一轮** Commit——须显式钉死。

**优点 / 缺点**

首包快。缺点：本轮新事实可能更晚可见；与现有冻提交模型别扭。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● Java 队列重做 Hermes **时机语义**即可；禁止嵌 Hermes 进程 |
| **框架适配** | ▲ 与 S6-a「本轮 Freeze 含记忆」冲突点多，等于改提交模型 |
| **2C2G** | ▲ 不挡首包（好）；仍占 1 线程 + 一次 LLM；与聊天峰值重叠时 2C 仍紧 |
| **本批** | **更后**（或你明示接受改提交/可见性模型） |

#### S1-e · S1-a + S1-b（每轮自动抽 + tool）

**对标与模块**

- **Open WebUI**：热路径 **tool** + 可选 **后台 review**（双路径并存）。
- **LangMem**：允许热路径 memory tools 与后台 manager **同时**存在。
- **Hermes**：内置 `memory` tool（热）+ Provider `sync_turn`（后）——形态上也是双轨。
- 本选项是「自动同步抽（a）+ 明示 tool（b）」，比 WebUI「tool+间隔 review」更重（每轮都 a）。

**抄什么 / 不抄什么**

- 抄：静默成长 + 明示控制。
- 不抄：双路径不经 Policy 去重就双写；不抄 Hermes 双后端语义分裂。

**本仓落点**

自动 Propose ∪ tool 草案 → **同一** S4 → 同一 Freeze。

**优点 / 缺点**

功能最全。缺点：测试面与每轮成本最大。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● Java |
| **框架适配** | ▲ 去重、幂等、双来源单测重 |
| **2C2G** | ■ **每轮 +记忆 LLM + tool 轮次**；2C2G **最重**，小机不优先 |
| **本批** | 可做但不推荐作 2C2G 默认 |

**S1 请回复一个代号：** `S1-a` / `S1-b` / `S1-c` / `S1-d` / `S1-e`  
（选 c/d 请写明：本批做轻量版 / 明确更后）

---

### S2 · Propose（抽什么）— **当前请选（可按 B/C 双轨分选）**

S2 回答：**claim 文本从哪来**（禁止正则/关键词表抽记）。  
在已锁定 **S0-b + S1-b+c** 下，通常 **热路径（B）与冷路径（C）会选不同 S2**，这是正常的。

```text
B 轨（tool）：回话模型在 Loop 里提交 facts     → 常选 S2-b（可叠 S2-c 收窄）
C 轨（Review Worker）：队列 Job 触发后另一次 LLM → 常选 S2-a（对齐 WebUI reviewer）
```

---

#### S2-a · 独立 LLM 结构化调用出 claim[]（≠ 聊天正文）

**对标与模块**

- **Mem0**：`Memory.add(messages, infer=True)` 时由 **infer 管道内的 LLM** 从对话抽出事实/偏好/决定（[add 文档](https://github.com/mem0ai/mem0/blob/main/docs/core-concepts/memory-operations/add.mdx)）。注意：Mem0 的调用常由宿主发起，但「抽什么」仍是**另一次模型推理**，不是聊天回复顺带写库。
- **LangMem**：`create_memory_manager` / `create_memory_store_manager` ——独立 manager 读 conversation/state，产出 insert/update（见 `langmem/knowledge/extraction.py` 一类指令与抽取）。
- **Open WebUI 冷路径**：`_generate_memory_operations` 用 `generate_chat_completion(..., stream=False)`，`metadata.task=memory_review`，输入=已有记忆+近讯转录，输出=JSON `operations[]`（`utils/memory.py`）。这正是 **C 轨**最贴近的对标。

**抄什么 / 不抄什么**

- 抄：专门一次结构化调用；输入可含近讯与已有 ACTIVE 摘要；输出 claim 列表或 add/replace/remove 操作草案。
- 不抄：Mem0 向量/图后端；把 infer 结果直接当 ACTIVE；reviewer 失败静默无观测（我们要 Job 状态/有限重试）。

**本仓落点（与已选 S1-C 对齐）**

- 跑在 **MemoryReviewWorker** 内，使用**可与回话分离的** `ModelPort` bean（不同 endpoint/模型均可）。
- 只产出 `ProposeResult` / operations 草案 → 再交 S3/S4 → 短事务 Commit。
- 主节点 Turn **不**同步等这次调用。

**优点 / 缺点**

优点：漏调 tool 仍能补记；整理/去重能力强（WebUI 明确要求优先 replace）。缺点：每次 Review 多一次 LLM；prompt 要控长度（WebUI 截断转录与已有条数上限可借鉴）。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● Java 调现有/新建 ModelPort；不嵌 Mem0/WebUI Python |
| **框架适配** | ● 挂 `MemoryReviewWorkerPort`；与 TurnEngine 解耦 |
| **2C2G** | ▲ 触发稀疏（每 10 轮或 idle 30min）则可接受；Worker 错峰；忌与回话同抢**本地**大模型显存 |
| **本批** | **C 轨强烈推荐** |

---

#### S2-b · 同一回话模型经 tool 提交 facts

**对标与模块**

- **Open WebUI 热路径**：`tools/builtin.py` 的 `add_memory(content, type, path?)`、`update_memory(operations[])` ——内容在 tool 参数里，宿主**不再**对用户句做二次抽取（但他们 tool **直写库**，我们不抄写缝）。
- **Mem0 × OpenClaw skill**：`memory-triage` skill 让 Agent 自评后 `memory_store` / `memory_add`，常配 `infer=false`（模型已整理好的 facts 原样存）（`openclaw/skills/memory-triage/SKILL.md`）。
- **LangMem** 热路径 memory tools：由 agent 决定何时 search/save。
- **Hermes** `memory_tool.py`：add/replace/remove 写 `MEMORY.md`/`USER.md`（文件真源；我们改 SQLite+草案）。

**抄什么 / 不抄什么**

- 抄：claim **来自 tool 参数**；宿主不二次「猜」用户原句；tool schema/description 约束「只记 enduring」。
- 不抄：tool 直写 DB/向量/MemFS；`infer=false` 跳过 Policy。

**本仓落点（与已选 S0-b / S1-B 对齐）**

- `ToolRuntime` → `MemoryToolDraft{claim, typeHint, ...}` → S3/S4 → 进 Freeze 或短事务。
- **不再**为 B 轨单独跑 S2-a（除非你显式要「tool 之后再 LLM 润色」——一般不需要，且费钱）。

**优点 / 缺点**

优点：零额外记忆 LLM；与用户「记住」意图同轮闭环。缺点：模型漏调就不记（靠 C 轨补）。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● Java ToolRuntime |
| **框架适配** | ● 已锁 S0-b 的自然延伸 |
| **2C2G** | ● 无额外定时抽记调用（成本已在回话 Loop 轮次内） |
| **本批** | **B 轨强烈推荐** |

---

#### S2-c · 仅明示「记住…」才允许提案（收窄 B，非正则库）

**对标与模块**

- **HANAGENT legacy**：`save_memory` 工具**描述**要求「用户明确请求记住时」才存。
- **Letta**：`/remember` 显式教。
- 与 S2-b 的差别：S2-b 允许模型对「类似可记信息」自主调 tool；S2-c 把允许面收到「明示记住」才出草案（可用 **tool description + 策略** 约束，或 Worker 拒非明示——**禁止**维护「记住|记得」正则表当唯一实现）。

**抄什么 / 不抄什么**

- 抄：窄触发，降误记。
- 不抄：正则扫用户句当抽记引擎。

**与已选 S1-B 的关系**

你已说「用户要求记住**或者有类似信息** → 模型自主调用」。这更接近 **S2-b（自主）**，而不是纯 S2-c。若选 S2-c，会削弱「类似信息」自主记，更多依赖 C 轨。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● |
| **框架适配** | ● 主要改 tool 说明与 Policy 门槛 |
| **2C2G** | ● |
| **本批** | 可选作 **B 的收窄**；与当前口述不完全一致 |

---

#### S2-d · 无自动 Propose，只靠人工 UI

**对标：** SillyTavern 核心 lorebook（用户维护）。  
**本批：** 不做成长主路径（可作 S11 人工录入补充）。

| 批判维 | 评定 |
|--------|------|
| **2C2G** | ● 最省 |
| **本批** | 不选作主路径 |

---

### S2 与双轨怎么配（请你选）

| 组合代号 | B 热路径 | C Review Worker | 含义 |
|----------|----------|-----------------|------|
| **S2-ba**（推荐） | S2-b | S2-a | 对齐 Open WebUI：tool 带事实 + 后台 LLM 出 operations |
| **S2-aa** | S2-a | S2-a | 热路径也再跑独立 LLM（贵，且与 tool 重复） |
| **S2-bc** | S2-b 再收成 S2-c | S2-a | 热路径仅明示记住；其它靠 30min/10 轮 review |
| **S2-b-only** | S2-b | （削弱 C） | 不符合已锁 S1-c |

**推荐默认：S2-ba** —— 与已锁 S0-b、S1-b+c、Job+Worker 一致。

**请回复：** `S2-ba` / `S2-aa` / `S2-bc` 或其它分轨说明。

---

### S3 · Shape（定轴 / 噪声 / 规范化）— **当前请选（可按 B/C 分选）**

S3 回答：草案如何带上 **kind / source / scope / sensitivityHint**，以及噪声是否 **DISCARD**。  
**禁止**正则把「住在→FACT」当生产分类器。

已锁 **S2-ba** 时的自然配对（可改）：

```text
B 轨（tool 已带 type/path） → 常选 S3-c（校验枚举，非法拒）
C 轨（Review LLM 出 operations）→ 常选 S3-a（轴已在同一次 JSON 里；代码只校验）
不要为 C 再加 S3-b 第二次分类 LLM（2C2G 翻倍）
```

本仓轴语义以 [memory.md](./memory.md) / 工作簿为准（content_kind、source_kind、scope、sensitivity…）；Open WebUI 只有 `user|context` + `path`，**映射时要加厚**，不能假装 WebUI 已有全轴。

---

#### S3-a · 与「同一次」LLM 输出带齐轴；代码只做枚举校验

**对标与模块**

- **Mem0 infer**：一次抽取结果已带类型化记忆（事实/偏好/决定等），不是先出纯文本再正则打标。
- **LangMem**：按 schema / profile|collection 让模型产出结构化字段。
- **Open WebUI reviewer（C 轨对标）**：同一次 `_generate_memory_operations` 的 JSON 已含 `type: user|context`、`path`、`action`；宿主 `validate_memory_operations` 做清洗与合法性检查（`utils/memory.py` + `routers/memories.py`）。

**抄什么 / 不抄什么**

- 抄：轴/类型由**同一次**记忆向 LLM（或 tool 结构化参数）给出；Java 只做白名单、必填、DISCARD 规范化。
- 不抄：用正则补 kind；把 WebUI 的二值 `user/context` 当成最终 content_kind 全集（要映射到本仓枚举，缺省策略写死）。

**本仓落点**

- **C 轨：** Review 的 structured output schema 直接要求本仓轴字段（或先出 WebUI 形再映射层）→ `ClassifyResult`。
- **B 轨：** 若 tool 参数已带齐轴，则 B 也可用 S3-a 语义（「同一次」=回话模型的 tool 调用，不再另调 LLM）。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● Java 校验；schema 在 prompt/tool JSON |
| **框架适配** | ● Port `MemoryTriage`/`Shape` 默认可为 ValidateOnly |
| **2C2G** | ● **零额外 LLM**（轴已在 S2 那一次里） |
| **本批** | **C 轨推荐**；B 若 tool 带齐字段也可 |

---

#### S3-b · 第二次 LLM 只做分类 / DISCARD

**对标与模块**

- 工程形态：专用 triage 模型；与「抽记」分离的 Port。
- Open WebUI 的 reviewer 是**另一次** LLM，但那是 **S2-a（抽+整理）**，不是「已有 claim 再分类一次」。
- Jev 若后挂，也属此类 Port（本批不实现）。

**抄什么 / 不抄什么**

- 抄：抽与分可替换。
- 不抄：本批为 C 再加第二次调用；Jev 冒充抽记。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● |
| **框架适配** | ● Port 可留 |
| **2C2G** | ■ 每次 Review **+1 LLM**；小机不优 |
| **本批** | **不推荐**（除非 S2 只出无类型纯文本——与 S2-ba 不符） |

---

#### S3-c · Tool / skill 要求填 category（或本仓轴字段）；非法拒收

**对标与模块**

- **Open WebUI tool**：`add_memory(content, type=user|context, path?)` ——类型在参数里；非法由后端 `normalize` / validate。
- **Mem0 openclaw memory-triage skill**：强制 `category` 枚举，批写同 category（`memory-triage/SKILL.md`）。

**抄什么 / 不抄什么**

- 抄：契约在 tool schema；宿主校验失败 → 工具返回错误，不落库。
- 不抄：无校验入库；仅用 WebUI 二值类型而不映射本仓轴。

**本仓落点（B 轨）**

- remember tool schema 要求：`claim` + `contentKind`（或可映射的 type）+ 可选 scope/sensitivityHint。
- 缺字段/非法枚举 → tool 失败或 DISCARD，**不**再调分类 LLM。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● |
| **框架适配** | ● 贴合已锁 S0-b / S2-b |
| **2C2G** | ● |
| **本批** | **B 轨推荐** |

---

#### S3-d · 本批不设细轴，自由文本

**对标：** legacy 极简 `MemoryEntry`；部分 MVP。  
**冲突：** [memory.md](./memory.md) 已定轴。  
**本批：** **不推荐**。

---

### S3 双轨组合（请选）

| 代号 | B | C | 说明 |
|------|---|---|------|
| **S3-ca**（推荐） | S3-c | S3-a | 对齐 Open WebUI：tool 带 type；review JSON 带 type；代码校验/映射本仓轴 |
| S3-aa | S3-a | S3-a | B 也靠「同一次」字段（tool 也算），与 ca 接近 |
| S3-cb | S3-c | S3-b | C 多一次分类 LLM → 不推荐 |

**请回复：** `S3-ca`（推荐）或其它。

---

### S4 · Policy（硬门槛裁决）— **当前请选**

S4 回答：草案能不能变成 `ApprovedMemoryChange`（ACCEPT / REJECT / SUPERSEDE / …）。  
**不写库**；写只在 S6/S11。模型 confidence **不能**绕过本层。

Open WebUI 几乎把「记不记」放在 **prompt/tool 说明**里，**没有**本仓这种独立 Policy 引擎——我们**必须自建**（产品不变量），不能「对齐 WebUI 就省略 S4」。

---

#### S4-a · 确定性规则引擎（推荐必选）

**对标与模块**

- **本产品**：[memory.md](./memory.md) §2.3–2.5（自动提升硬门槛、敏感 S0/S1/S2、冲突 SUPERSEDE/HOLD）；[06 工作簿](../guide/06-memory.md) MemoryPolicy 练习区。
- **OpenClaw**（只借思想）：写时 provenance / 来源门、forget 墓碑；**不**抄 Deep 评分提升（已否决启发式门控）。
- **对照 Open WebUI**：他们靠 reviewer/tool 文案约束「别记临时情绪」；我们把同等意图**代码化**（敏感词表、冲突、companion 错配、INFERRED 拒自动 ACTIVE 等），可测、不漂移。

**抄什么 / 不抄什么**

- 抄：硬门槛留内核；敏感先于 confidence；明确纠正 → SUPERSEDE；含糊冲突不静默盖；可 DISCARD/REJECT 不落库。
- 不抄：OpenClaw 候选评分；用抽记正则冒充 Policy；WebUI「无 Policy 只靠 prompt」。

**本仓落点**

- B、C 两轨草案**都进同一** `MemoryPolicy`（或同一接口两配置，决策种类一致）。
- 输出 `MemoryEvaluation` → Approved* 才进 Freeze（B）或 Review 短事务（C）。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● 纯 Java 规则；敏感可用词表（安全门 ≠ 抽记启发式） |
| **框架适配** | ● `MemoryPolicy` Port，Turn/Worker 共用 |
| **2C2G** | ● 无 LLM；CPU 可忽略 |
| **本批** | **必选倾向** |

---

#### S4-b · LLM 参与冲突消解，规则只留敏感底线

**对标：** LangMem manager 合并/update；CrewAI consolidation（keep/update/delete/insert_new）。  
**抄：** 复杂矛盾交给模型。  
**不抄：** 敏感也交给模型。  

| 批判维 | 评定 |
|--------|------|
| **2C2G** | ■/▲ 又一次 LLM；可更后叠在 C 轨 |
| **本批** | **更后**（本批用 S4-a 足够） |

---

#### S4-c · 写入前用户批准

**对标：** Cursor Memories（保存前批准）。  
**抄：** 人审门。  
**不抄：** 作唯一路径（陪伴摩擦大）。  

| 批判维 | 评定 |
|--------|------|
| **本批** | 可叠 S1 场景；**不作默认唯一** |

---

### S4 怎么回

| 代号 | 含义 |
|------|------|
| **S4-a**（推荐） | 本批只做确定性 Policy |
| **S4-a+c** | a + 敏感条人批（可选） |
| **S4-a+b** | a + 更后 LLM 消解（本批可只留 Port） |

**请回复：** `S4-a`（推荐）或 `S4-a+c` 等。

**已选：S4-a-min**（用户 2026-09-23）：确定性 Policy，**仅拦截密钥类秘密**；收入/疾病/冲突 SUPERSEDE 细则等 **本批不做限制**（与 memory.md 全量敏感口径相比为收窄；施工单须写死密钥判定范围）。

密钥类（本批写死意向，实施可微调词表/模式，**仍不是抽记启发式**）：

- 密码、口令、passphrase  
- API token / access_token / refresh_token / Bearer  
- 私钥 / private key / PEM 块  
- 银行卡号+验证码一类明显凭据组合（若仅「我用某银行」而无号码 → 不拦）

**本批明确不拦（举例）：** 城市/偏好/关系称呼、收入工资表述、健康话题、含糊冲突（可先 ACCEPT 并存或后靠 SUPERSEDE/纠正 API；不在 S4 做复杂冲突机）。

B/C 两轨共用此最小门。

---

### S5 · Relationship（关系状态，与记忆并行）— **当前请选**

S5 管的是 **relationship_state**（称呼、边界、互动契约等），**不是** memory_record 里的偏好/事实条。  
与记忆共用 Commit 事务边界（B 热路径）或可另定；**禁止**旧半成品那种称呼/边界**正则**抽取。

Open WebUI **没有**对等的 Relationship 子系统（只有 user/context 记忆）。本层主要对标本仓 [memory.md §3](./memory.md) + Letta core 里「身份/偏好规则常驻」的思想。

---

#### S5-a · 与记忆同一 Propose 附带 relationship hints

**做法：** C 轨 Review LLM（或 B 轨某次结构化输出）顺带给出 `preferredAddress` / `boundaries` 等 hint → RelPolicy（可极简）→ `ApprovedRelationshipChange`。  
**对标：** Letta core block 常驻；本仓 RelationshipState。  
**2C2G：** ▲ 若绑在 C 的同一次 LLM 里则无额外调用；若每轮都提则贵。  
**本批：** 可做，但要防乱改称呼。

#### S5-b · 独立 RelPropose LLM

再开一次模型只推关系。**2C2G ■** → 不推荐本批。

#### S5-c · 仅明示改称呼/边界（经 tool 或 LLM 解析，非正则）

**做法：** 用户说「叫我小韩」「不要主动…」→ 走 remember 类 tool 的关系变体，或 C 仅在明示时改。  
**对标：** Letta `/remember` 显式教；legacy 显式工具精神。  
**2C2G ●**  
**本批：** 与「除密钥外少限制」一致时，可用 tool 明示改关系。

#### S5-d · 本批不做关系写，只读空/默认快照

**做法：** `beforeTurn` 可读空 snapshot；afterTurn 不产生 change。  
**2C2G ●**  
**本批：** 切片时可先做，记忆优先。

---

**已选：S5-c-tool**（用户 2026-09-23）。

### 已锁定含义 · S5-c-tool

- 向模型暴露 **关系专用 tool**（与 remember **分 descriptor**；禁止一个 tool 兼写 memory + relationship）。
- **何时调：**  
  1. 用户明示改称呼 / 边界 / 互动契约；  
  2. C 轨整理 / Review（或会话整理）发现「用户希望改变关系」→ **由模型调用本关系 tool**（不是正则扫句；不是 Memory 草案里顺带改 rel）。
- **写缝：** tool → `RelationshipToolDraft` →（正文若含密钥可复用 S4-a-min）→ `ApprovedRelationshipChange` → 热路径进当前 Turn `FreezeCommitPlan`；整理路径无当前 Turn 时走**短事务**（与 Memory Review 短事务对称，仍经 Committer 语义）。
- **解耦（必须）：**  
  - Port / Adapter 独立于 `MemoryPropose`；  
  - Memory 写入路径 **不得**偷偷改 `relationship_state`；  
  - 记忆失败与关系 revision CAS 失败互不重放对方的 Approved*；  
  - Review Job 可提示「应调关系 tool」，但落库仍只认关系草案类型。
- **禁止：** 旧半成品称呼/边界正则；记忆 JSON 里夹带 rel 字段当正式写。

---


### S6 · Freeze + Commit（正式写库）— **当前请选**

S6 回答：**谁在什么事务边界里把 Approved\* 写成 SQLite 真行**。  
前面 S2–S5 都只产草案；**只有这里（和 S11 短事务）允许 INSERT/UPDATE**。

在已锁双轨下会出现两条写缝，必须分清：

```text
热路径 B（本轮有 Turn）
  记忆 Approved* + 关系 Approved* + 助手消息
  → FreezeCommitPlan → 同事务 commit          ← 典型 S6-a

冷路径 C（Review Job / 空闲整理，往往无「当前正在 seal 的 Turn」）
  → 短事务 MemoryCommand / ReviewCommit        ← 仍是 Committer 语义，不是业务随便写
  关系 tool 若在整理中触发且无当前 Turn → 同短事务族
```

Open WebUI：**tool/review 直接写 memories 表**，与 chat 完成可分裂——我们**不抄**这种松散度。

---

#### S6-a · 同事务冻提交（热路径必选倾向）

**对标与模块**

- **本产品** 0.2.1/0.2.2：`TurnCommitter`、`FreezeCommitPlan`、`CommitTurnPlan`、`SqliteTurnCommitter`。
- 时机思想对齐 WebUI/LangMem「终稿后再派生」，但写缝更严。

**抄什么 / 不抄什么**

- 抄：冻计划含 ApprovedMemory* / ApprovedRelationship*；Message + Turn COMPLETED + memory + rel **同事务**；COMPLETED 重试不双写；失败整笔回滚。
- 不抄：WebUI 记忆与聊天可分裂提交；绕过 Freeze 的「先写记忆再补消息」。

**本仓落点**

- B 轨 tool 草案过 S4 后进本轮 Freeze。  
- S5 关系 tool 在同轮触发时，rel change **同一** Freeze（解耦指 Port，不是拆事务——同轮仍原子）。

| 批判维 | 评定 |
|--------|------|
| **语言** | ● 已有 Java Committer |
| **框架适配** | ● 0.2.2 核心路径扩展字段即可 |
| **2C2G** | ● 无额外 LLM |
| **本批** | **热路径必选** |

---

#### S6-b · 记忆（及整理态关系）独立短事务

**对标：** Open WebUI `routers/memories.py` 与 chat 完成分离；Hermes 后台队列写。  

**在本仓的合法用途（收窄）：**  
仅用于 **C 轨 Review Job / 无当前 Turn 的整理写**——仍须：CAS、稳定错误码、真写、可观测；**禁止**热路径聊天也走这条来「图省事」。

| 批判维 | 评定 |
|--------|------|
| **框架适配** | ▲ 要与 Freeze 恢复模型并存，边界写清 |
| **2C2G** | ● |
| **本批** | **冷路径需要**；热路径禁止用它替代 S6-a |

---

### S6 组合（请确认）

| 代号 | 含义 |
|------|------|
| **S6-a+b**（推荐） | 热路径 **S6-a**；Review/无 Turn 整理写 **S6-b 短事务**（仍 Committer 族） |
| **S6-a-only** | 强迫一切进某轮 Freeze（C 轨别扭） |
| **S6-b-only** | 热路径也短事务（破坏冻恢复，不推荐） |

**请回复：** `S6-a+b`（推荐）或其它。

**已选：S6-a+b · S7-a · S8-a-min · S10-a-mem · S11-a · S12-weak-B**（用户 2026-09-23；R1 覆盖原 S8-defer/S12-x）。详见 §4 / §4.1。

---

### S7 · Store

#### S7-a · SQLite 表 + 只读 Store

| | |
|--|--|
| **对标** | 本产品 SQLite 路线；Mem0 OSS 亦有 SQLite history（仅形态参考） |
| **模块** | 本仓 Flyway + Store port；Mem0 `mem0/memory/storage.py`（不抄其无 source 外键弱点——我们要 source_turn_id） |
| **本批** | **必选** |

#### S7-b · Letta MemFS 文件树

| | |
|--|--|
| **对标** | Letta MemFS（git-backed 路径记忆） |
| **模块** | [MemFS 概念](https://github.com/letta-ai/letta-docs-md/blob/main/concepts/memfs/index.md) |
| **本批** | 更后/不做 |

#### S7-c · 外挂 Mem0 Provider

| | |
|--|--|
| **对标** | **Hermes** `plugins/memory` + Mem0 Provider |
| **模块** | Hermes `memory_provider.py` / `plugins/memory/` |
| **不推荐** | 语义漂到 Provider（调研已警告） |
| **本批** | 不推荐 |

---

### S8 · Recall / Rank

#### S8-a · 过滤 + 确定性加权排序（无向量）

| | |
|--|--|
| **对标** | **Generative Agents** `MemoryStream` 检索：recency×relevance×importance；**CrewAI** recall：semantic+recency+importance |
| **模块** | StanfordHCI `MemoryStream` 归一化加权；CrewAI Memory recall 文档 |
| **抄** | 多信号**排序**（本批可用 recency/sourceTrust/kindBoost；relevance 无向量则弱化） |
| **不抄** | importance 再调一次 LLM；用排序分写进 Prompt 操纵人设 |
| **本批** | 可做 |

#### S8-b · 向量语义检索

| | |
|--|--|
| **对标** | Mem0 search；LangMem semantic search；ST CharMemory 向量块 |
| **本批** | **更后** |

#### S8-c · ACTIVE 列表截断 Top-N

| | |
|--|--|
| **对标** | 极简宿主；Claude Code 载入 MEMORY.md 前 N 行/25KB 上限（[How Claude Code works](https://code.claude.com/docs/en/how-claude-code-works)） |
| **抄** | 硬预算截断 |
| **本批** | 可作过渡 |

#### S8-d · LLM 每轮挑选注入

| | |
|--|--|
| **对标** | 少见；成本高 |
| **本批** | 不推荐 |

---

### S10 · Assemble（S9 随关系选项）

#### S10-a · 近讯 + MemoryContext + Rel 摘要 → AgentInput

| | |
|--|--|
| **对标** | 本仓 0.2.2 `ContextAssembler`；Letta「core 每回合加载」；Cursor Rules 注入 |
| **模块** | `ContextAssembler`；Letta system/ core 路径每回合加载 |
| **抄** | 装配结构固定；召回结果变文本块 |
| **本批** | **必选** |

---

### S11 · Command

#### S11-a · HTTP 列表 / 纠正 / 遗忘

| | |
|--|--|
| **对标** | Open WebUI 设置页记忆 CRUD；Mem0 update/delete/history API；OpenClaw `memory forget`（含 dry-run 思想） |
| **模块** | WebUI `routers/memories.py`；Mem0 Platform update/delete；OpenClaw forget 文档 |
| **抄** | 用户可查可改可忘；forget 留墓碑 |
| **不抄** | 假 200；无 CAS |
| **本批** | **推荐** |

#### S11-b · 仅模型 tool 纠正

| | |
|--|--|
| **对标** | WebUI/LangMem 仅 tool 侧 update |
| **不推荐** | 作唯一入口 |
| **本批** | 不推荐唯一 |

#### S11-c · 本批不做

| | |
|--|--|
| **本批** | 切片可选 |

---

### S12 · Maintain

#### S12-x · 不做

| | |
|--|--|
| **本批** | **默认** |

#### S12-a · 间隔 LLM review 合并/删

| | |
|--|--|
| **对标** | Open WebUI 后台 review；LangMem ReflectionExecutor |
| **模块** | WebUI `utils/memory.py`；LangMem ReflectionExecutor |
| **本批** | 更后 |

#### S12-b · dreaming / 候选评分提升

| | |
|--|--|
| **对标** | OpenClaw dreaming；Letta sleeptime/dreaming |
| **模块** | OpenClaw `dreaming` / memory-core；Letta `/sleeptime` |
| **本批** | **不做**（含启发式评分） |

---

## 3. 推荐组合（仅参考）

| 目标 | 组合 |
|------|------|
| 成长默认同步 | S0-a · S1-a · S2-a · S3-a · S4-a · S5-a · S6-a · S7-a · S8-a · S10-a · S11-a · S12-x |
| 明示记住为主 | S0-b · S1-b · S2-b · S3-c · S4-a · S5-c · S6-a · S7-a · S8-a · S11-a · S12-x |
| **本仓已选方向** | 见 §4 全表（已逐层锁定） |

---

## 4. 选定记录（**已齐 · 2026-09-23**；**§4.1 R1 覆盖 S8/S10/S12**）

```text
S0:  S0-b
S1:  S1-b + S1-c
       · review_interval_turns = 10
       · idle_timeout = 30 minutes
       · 执行面 = 队列 Job + 可替换 Worker（可迁副节点）
S2:  S2-ba     B=tool facts；C=Worker 独立 LLM（对齐 Open WebUI）
               + 同一次输出含规范化 claim + importance（见 §4.1）
S3:  S3-ca     B=tool 类型校验；C=同次 JSON 轴校验/映射
               + importance 必填 + claim 规范化检查（见 §4.1）
S4:  S4-a-min  仅拒密钥类；其余一律可存（本地私人助手；无 MemorySensitivity / S1 加密）
S5:  S5-c-tool 关系专用 tool；明示或整理发现时调；与记忆解耦
S6:  S6-a+b    热路径 Freeze 同事务；冷路径/无 Turn 短事务
S7:  S7-a      SQLite memory_record + relationship_state；Store 只读；含 importance
S8:  S8-a-min  【R1】score = w_r×decay + w_i×importance（固定 HL=30d）；无向量
S9:  随 S5：beforeTurn 可读 snapshot（有则注入）
S10: S10-a-mem 【R1】近讯 + 衰减召回记忆 + Rel 摘要 → AgentInput
S11: S11-a     HTTP GET ACTIVE + correct(SUPERSEDE) + forget(FORGOTTEN)
S12: S12-weak-B【R1 覆盖原 S12-x】确定性扫墓（score+minAge → FORGOTTEN）；不做 dreaming
```

### 4.1 R1 增补（用户 2026-09-23 · 覆盖 S8-defer / S12-x）

施工正文：[k03-memory.md](../plans/k03-memory.md) §0.1。

| 项 | 锁定 |
|----|------|
| 范围代号 | **R1** |
| importance | 模型在 S2 同一次输出打分 \[0,1\]；禁止第二次只打分 LLM |
| claim 规范化 | 「今天」→ 绝对日期；「这里」→ 具体地点；宿主注入时间/地点锚；禁止正则换日期 |
| A 召回衰减 | `score = w_r×0.5^(age/30) + w_i×importance`（CrewAI 形；固定 HL） |
| 弱 B | `score < ε` 且 `ageDays > minAge` → FORGOTTEN；**或** `importance < 0.35` 且 `ageDays > 14`（A+B）；零 LLM |
| 仍不做 | 向量、dreaming、Deep 启发式评分提升、手改分 UI、**MemorySensitivity / S1 加密**（本地私人助手：仅挡密钥） |

### S8-defer 原含义（历史；已被 §4.1 覆盖）

- 原意向：本批 Assembler 记忆可空。  
- **现行：** 以 §4.1 / k03 R1 为准，须实现 S8-a-min + 弱 B。

### 已锁定 · S0-b

- ToolRuntime 注册记忆类工具；Adapter **禁止**直写表；草案 → S4 → S6/S11。  
- 对标：Open WebUI builtin tools、Letta `/remember`、legacy `save_memory`、Hermes/OpenClaw 热路径（写缝收紧）。

### 已锁定 · S1 = B+C（用户 2026-09-23）

对标 **Open WebUI 双轨**，并加空闲触发；写缝仍本仓收紧。

| 轨 | 触发 | 行为 |
|----|------|------|
| **B（热）** | 用户要求记住 / 同类可记信息 → 模型**自主**调 remember 类 tool | 草案进本轮（或紧随）S4→Commit；**不**直写库 |
| **C（冷）** | ① **固定 user-turn 间隔 = 10**；② **idle = 30 分钟** | **不**挡聊天；投递 Review Job → 独立 Worker → Policy → **短事务 Commit** |

**明确不做本轨：** 每轮同步 Propose（S1-a）；同机为记忆再加载一份本地大模型。

**执行面（已钉）：** 队列 Job + 可替换 Worker（同 JVM 起步 → 进程/副节点）。

**S6–S12（用户 2026-09-23 + R1）：** S6-a+b · S7-a · **S8-a-min** · **S10-a-mem** · S11-a · **S12-weak-B**（覆盖原 S8-defer / S12-x）。

---

下一动作：按 [k03-memory.md](../plans/k03-memory.md) R1 分文件审阅实施（阶段 A→E）。

### S1-C 解耦与 2C2G / 多节点（已定方向）

问题：C 若在主 JVM 同步或同线程抢 `ModelPort`，会与回话争 API/本地推理。

**结论分层：**

| 做法 | 同机 2C2G | 多节点以后 |
|------|-----------|------------|
| 仅 `asyncio`/线程在主进程调模型 | 不挡首包，但仍与聊天争**同一**模型配额与 CPU | 难迁走 |
| **同机再开一进程跑 Worker** | 隔离崩溃/线程；若两边都打**云 API** → 主要争的是配额与带宽，堆可略分；若两边都跑**本地模型** → **■ 红灯**，2G 装不下双推理 | 仍占主机器 |
| **Outbox/Job 队列 + Worker Port**（推荐） | 本批可先 **同进程单线程 Worker**（语义已解耦）；进程拆分可选 | Worker 改打副节点 HTTP/租约，**主节点只投递 Job** |

**本仓钉死接口形态（实施时写进 k03）：**

```text
主节点 Turn 完成 / 空闲检测
  → MemoryReviewScheduler.enqueue(ReviewJob)   // 只写队列，立即返回
MemoryReviewWorker（可替换执行面）
  → 调记忆向 ModelPort（可与回话不同 bean/endpoint）
  → S3/S4（或 review 专用 Policy）→ MemoryCommand/短事务 Committer
  → 失败重试有限；绝不回调阻塞用户 SSE
```

- **语言：** ● 全 Java；不嵌 Open WebUI Python。  
- **框架：** ▲ 需最小 Job/lease（可先 SQLite 表 + 单消费者）；与 memory.md §4 对齐收口。  
- **2C2G：** ▲ C 触发稀疏则可接受；Worker 与聊天 **错峰**（空闲触发优先、间隔触发可延迟到空闲）；云 API 时同机双进程收益有限，**解耦队列才是多节点钥匙**。  
- **副节点：** v0.3 多节点时，同一 `MemoryReviewWorker` Port 换远程实现；主节点不跑 review LLM。

### 待钉参数（选完 S2 后可写进 k03）

- [x] `review_interval_turns` = **10**（用户 2026-09-23）  
- [x] `idle_timeout` = **30 分钟**（用户 2026-09-23）  
- [x] Review 执行面 = **队列 Job + 可替换 Worker**（同 JVM 单消费者起步 → 可换进程/副节点）  
- [ ] 空闲检测挂点（会话 `last_activity_at` 定时扫，实施写死）  
- [ ] Review 与 tool 同 subject 去重规则  

### 已锁定 · Review 执行面

```text
MemoryReviewScheduler.enqueue(job)     // 主路径只投递
MemoryReviewWorkerPort.execute(job)    // 可替换：InProcess → Process → RemoteNode
  → 记忆向 ModelPort（可与回话分 bean）
  → Shape/Policy → 短事务 Commit
```

---

