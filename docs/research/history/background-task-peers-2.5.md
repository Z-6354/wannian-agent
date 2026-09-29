# 2.5 · BackgroundTask / 同业对照与未决

`status`: **历史 · 已交付（决议保留）** — 2026-09-29 · 审阅 → [k05-task-system-logic-audit-2.5.md](../../plans/archive/2.5/reviews/k05-task-system-logic-audit-2.5.md) · 归档 [archive/2.5](../../plans/archive/2.5/README.md)
`purpose`: 开工 2.5 前对照同业与本仓现状；标出未决与可优化点。决议已吸收进版本计划。
`note`: **2026-09-26 用户确认**：用户向定时推 / 定时 Task **并入 2.5**；原 Q5「真调度→2.6」仅保留 **WORLD_TICK/世界侧**；见计划 [k05-task-background.md](../../plans/archive/2.5/k05-task-background.md) Q5/Q9。
`plan`: [k05-task-background.md](../../plans/archive/2.5/k05-task-background.md)

---

## 1. 本仓已钉死（勿在同业对照里冲掉）

| 不变量 | 出处 |
|--------|------|
| Task +「已接受」回复 **同事务**；禁止先回后写 | contract §10 / §11.2 |
| SubAgent **不**直写 Memory/Relationship、**不**冒充终答；完成由 Kernel 经 Outbox 交付 | contract §6.3 / §11.2 |
| `TaskDraft` 只经 `CommitTurnPlan`；调度器只调 `dispatchNext` | kernel-reference §8 / §后台化 |
| 重试 = **新 Run**；`LOST` 不复活；旧 attempt 迟到结果无效 | checklist 2.5 |
| 聊天插话仍是 **followup**；BackgroundTask 不占聊天 flight | [in-flight §8](./in-flight-user-message.md) |
| 可预留 `WORLD_TICK` **类型名**；不实现世界树；**用户向定时推已改口进 2.5**（≠ WORLD_TICK） | [world-evolution §4](./world-evolution-and-extensions.md) · 计划 Q5/Q9 |
| 陪伴对话 **默认前台** | [wannian-loop-modules §2.11](./wannian-loop-modules.md) |

### 代码现状（已验证）

- `AgentOutcome.BackgroundAccepted` 仍是 `String taskProposal` 占位。
- `TurnEngine` 收到 `BackgroundAccepted` → `BACKGROUND_NOT_ENABLED` 失败路径。
- `SqliteTurnCommitter`：`taskDraft != null` → `UNSUPPORTED_EXTENSION`。
- **并行已有**：`memory_review_job` + lease + `InProcessMemoryReviewWorker`（2.3 S1-c），**不是** `TaskRuntime`。

---

## 2. 同业对照（主源）

### 2.1 OpenClaw — 三层拆开（最值得抄边界）

主源：

- Tasks：https://docs.openclaw.ai/automation/tasks  
- Cron/Automations：https://docs.openclaw.ai/automation/cron-jobs  
- Cron vs Heartbeat：https://docs.openclaw.ai/automation/cron-vs-heartbeat  
- Heartbeat：https://docs.openclaw.ai/gateway/heartbeat  

| 概念 | 角色 | 对本仓含义 |
|------|------|------------|
| **Automations / cron** | **调度器**（何时跑） | ≈ **2.5 用户向定时模块**（挂 Task 时间字段）；WORLD_TICK/世界侧仍 **2.6** |
| **Heartbeat** | 主会话 ambient 巡检；**不**建 task 记录 | ≈ 世界线/陪伴巡检；属 v3 叙事，勿塞进 K05 实现 |
| **Tasks** | **活动账本**（跑了什么、成败）；不是调度器 | ≈ 本仓 `BackgroundTask` + `SubAgentRun` |

OpenClaw 启发（可吸收）：

1. **Task ≠ Scheduler**：账本与定时唤醒分开。本仓 `WORLD_TICK` 应是 Task **类型/来源**，调度器另件。  
2. **Notify policy**：`done_only` / `silent` / `state_changes`；自动化默认 silent，子代理默认完成才通知。本仓契约只写「Kernel 生成交付事件」，**未定**是否每类 Task 都要用户可见消息。  
3. **完成可 wake 主会话 / heartbeat**，而不是轮询；本仓已有 Outbox/SSE，方向一致。  
4. **普通聊天与 heartbeat 不建 task**——避免把每轮 Turn 记成 BackgroundTask。

勿照抄：Gateway 多会话 lane、channel 直投、ACP/CLI 运行时类型膨胀。

### 2.2 Claude Code — 前台/后台 subagent

主源：https://code.claude.com/docs/en/sub-agents · https://code.claude.com/docs/en/agents

| 点 | 事实 | 对本仓 |
|----|------|--------|
| 默认后台 subagent（较新版本） | 主会话可继续；结果回主 Agent | 对齐「长任务不占聊」 |
| Subagent **只回主 Agent** | 用户不直接跟子代理聊 | 对齐「SubAgent 不冒充终答」 |
| `/tasks` + `TaskStop` | 用户可见任务与取消 | UI 是否进 2.5 **未定** |
| Agent teams / agent view | 多会话协作 | **超出** v2 单核 |

勿照抄：把「编码并行 subagent」默认打开——陪伴产品要避免「分心消失」感（modules §2.11）。

### 2.3 OpenAI Agents SDK — Handoff ≠ BackgroundTask

主源：https://openai.github.io/openai-agents-python/handoffs/

- Handoff = **同一 run 内**把控制权交给另一 Agent；不是脱离 Turn 的持久 Task。  
- 本仓若把「后台」做成 handoff，会冲掉「原 Turn 完成 + 可继续聊」验收。

### 2.4 工具目录里的近义词（[ten-agent-tools](./ten-agent-tools.md)）

| 语义 | 常见名 | 与本仓 BackgroundTask |
|------|--------|------------------------|
| 待办清单 | `todo_write` / `TaskTrackerTool` | **规划 UI**，不是执行账本 |
| 子代理委派 | `subagent` / `delegate_task` / `sessions_spawn` | 接近 SubAgentRun |
| 调度 | `cron` / `schedule_*` / heartbeat | 接近 WORLD_TICK / v3 |
| 后台 job | `job_*` / `agents_wait` | 接近 Task 账本 |

MVP 必须分清：**Todo ≠ Task ≠ Cron**。

### 2.5 陪伴侧（仓内已摘）

[world-evolution](./world-evolution-and-extensions.md)：Tanya 60min heartbeat（多数沉默）、Sage 概率心跳、OpenClaw cron 主动投递。共性：**生活模拟 ≠ 每拍烦用户**；正式投递经通道/Outbox。

---

## 3. 本仓内部张力（查阅后发现）

| # | 张力 | 说明 |
|---|------|------|
| A | **双套后台 Job** | Memory Review 已有独立 job/lease/worker；2.5 再上 TaskRuntime → 两套状态机/并发策略，2C2G 争用风险（memory-system 已标红灯） |
| B | **触发面未定** | 手册：模型提出 + `BackgroundPolicy`；尚无「用户点后台」「工具 create_task」「系统 enqueue」是否同入口 |
| C | **Run 里跑什么** | 契约有 `LocalTaskExecutor`，未定：工具脚本 / 窄 Loop / 仅 MemoryReview 类 Job / 占位 echo |
| D | **完成怎么露脸** | 「Kernel 交付事件」= 助手 Message？System？仅 SSE toast？是否开新 Turn？与 followup 抢序？ |
| E | **G 文「消息中心→2.5」** | checklist 2.5 **未列**消息中心；可能是笔误或范围漂移 |
| F | **`WORLD_TICK` 深度** | 仅枚举占位 vs 空调度器 stub vs 完全不进 DDL |
| G | **命名漂移** | `CommitTurnPlan.taskDraft` vs 架构文 `backgroundTaskDraft`；`BackgroundAccepted.taskProposal: String` |
| H | **memory.md §4 Memory Job** | 草案仍「未确认」，但 2.3 已落地 review job——文档与实现需在 2.5 叙事里对齐「是否并入 TaskRuntime」 |

---

## 4. 已吸收进决议的优化（对照 §5）

1. Review 与 TaskRuntime 分期；SYSTEM 预留 → Q1  
2. Notify / Idle·Busy 交付 → Q4  
3. MVP 只读长工具批 → Q2  
4. 并发门闩 **1+1 执行槽**（可并存）+ **模型多发送窗口**（2026-09-27 用户改口：不因争模型 defer）→ Q7（Busy≠停后台）  
5. 任务侧栏与消息中心解耦 → Q4-U / Q6-L  

---

## 5. 决策题与已定决议

> **已定（2026-09-26）：**  
> Q1=A（+SYSTEM 预留）· Q2=B · Q3=A（+定时窄创建缝）· **Q4=用户定稿（idle/busy）+ 任务侧栏** · **Q5 改口：用户向定时→2.5；WORLD_TICK→2.6** · Q6→**2.5.10**（消息中心；号随重切） · Q7=A · Q8=A · **Q9=定时=Task 属性，不另建 Cron 表**

### 5.0 已定摘要

| 题 | 决议 |
|----|------|
| Q1 | A：Review 不迁；预留 `TaskSource.SYSTEM`；更后系统活迁 Task |
| Q2 | B：SubAgentRun = 只读长工具批 |
| Q3 | A：Loop+Policy；时机×重复正交；**创建前用户审核** |
| Q4 | 见 §5.4；**任务侧栏**进 **2.5.9** |
| **Q5** | **改口：** **(A) 用户向定时 Task + 定时模块 → 2.5**；**(B) WORLD_TICK/世界侧调度 → 仍 2.6**（不对用户说话） |
| Q6 | → **2.5.10**（右上角消息弹窗后端）；见 §5.6 |
| Q7 | A：**1+1 执行槽**（后台 1 Run + 前台 1 主 Loop 可并存）+ **模型多发送窗口**（**2026-09-27 用户改口**：可同时调模型，不因争模型 defer）；Busy≠停后台 |
| Q8 | A：旁线不进 |
| **Q9** | 定时挂 Task 账本；不另建 Cron 表 |
| **Q10** | relative/at/every/cron；禁 RRULE 主存 |

### 5.1 Q1–Q3 要点

**Memory Review** = 系统间隔打扫记忆库（silent，专用 job 表）。  
**TaskRuntime** = 用户向长差事账本（确认同事务 + 可汇报）。  
本版两套并存；预留 SYSTEM 供更后把 Review 等迁入短命 Task。

Q2=B：Run 内只读长工具批，不做第二套完整 Loop。  
Q3=A：只经 Loop+Policy；SYSTEM 仅类型预留。

---

### 5.4 Q4 · 任务完成交付（**用户定稿**）

#### 产品规则（**2026-09-26 再定稿** · 权威见计划 §0.1 / Q4）

| 会话状态 | 行为 |
|----------|------|
| **Idle**（该会话无进行中的对话轮） | Task（或定时的一次执行）完成后：**系统唤起模型**，由模型**主动发消息给用户**（durable；禁止 idle 丢弃；禁止仅日志）。 |
| **Busy**（当前正有对话轮在执行） | 结果排队；**等当前对话完成后**，主回复后用 **虚线分隔附带**发送。 |

补充：

- Task 本质=后台执行；模型给出两种：**立即执行**（常态、单次结束）/ **定时执行**（单次或多次）。  
- Busy 呈现默认虚线；气泡/模型续写皮肤更后。  
- SubAgent 仍不冒充终答；Kernel 持有结果后按上表交付。

#### 与先前 A/B/C · T1/T2 的关系

| 概念 | 定稿落点 |
|------|----------|
| 相对 Codex stock（B+T2） | **Idle 不沉默**（避免 #33712 类翻车）；Busy 不抢 flight 硬开第二 Turn |
| 相对 C（分级） | 用户向 Task **要可见汇报**；SYSTEM/内部仍可 silent（与 Q1 一致） |
| 相对 T1/T2 | **默认不因完成新开独立用户 Turn 抢 followup**；Idle 主动汇报优先走 Message+Outbox（类 T2+可见）；Busy 挂到当前主 Loop 收口呈现。若 Idle 汇报需要模型润色，可另开 **有界续写**（计划里写清 CAS/不与 followup 双跑），不默认无限 TriggerTurn |
| Codex 可抄 | QueueOnly vs 唤醒旋钮思想；#32188 的 exactly-once / busy 排队 / cancel 不假唤醒 |
| Codex 勿抄 | idle 丢弃完成事件；默认永远等用户 nudge |

#### 任务侧栏（进 2.5，非消息中心）

| 项 | 说明 |
|----|------|
| **做什么** | 侧栏列出模型发布的 Task：状态、详情、结果、失败原因等；可点开看 |
| **何时做** | **2.5 基础（TaskRuntime/账本/交付）完成之后** 的 UI 阶段（**2.5.6**）；不挡内核先入仓 |
| **对标** | Claude /tasks；OpenClaw tasks list；本仓 chat 侧栏扩展 |
| **不做（本侧栏）** | 版本更新提醒、通用报错弹窗聚合 —— 那些属 **消息中心（2.5.10）** |

Codex 对照原文仍保留于历史选项讨论；定稿以本小节为准。

---

### 5.5 Q5 · 定时 vs WORLD_TICK（**2026-09-26 改口**）

| 项 | 决议 |
|----|------|
| **2.5 · 用户向定时** | 同一 Task 账本加 `schedule_kind` / `next_fire_at` 等；**定时模块**到期对用户 durable 推（及/或再跑工具批）。见计划 Q5(A)/Q9 / **2.5.6** |
| **2.5 · WORLD_TICK** | 仍仅**类型名占位**；不跑世界心跳 |
| **2.6** | 生命周期探针 + WORLD_TICK **世界侧**调度/探针相关（**不对用户定时推**；世界树本体仍 v3） |

对标：OpenClaw Task≠Scheduler（分层语义保留；本仓 Scheduler 只服务用户向 Task 账本）。  
**作废：** 原「本版无调度器、用户定时也等到 2.6」。

---

### 5.6 Q6 · 消息中心 → **2.5.10**（号随重切）

**澄清（用户 2026-09-26）：** k04-g 所说「消息中心」= **右上角消息弹窗的后端**，不是任务侧栏。

| 项 | 决议 |
|----|------|
| **本版主线（2.5 基础）** | **不做**消息中心 |
| **阶段 2.5.10** | 在 Task 基础 + 定时模块解耦完成后做：消息中心 **整体架构解耦** + 将部分 **报错通知** 迁入；载荷包括但不限于：版本更新提醒、任务完成/定时提醒、错误通知等 |
| **与任务侧栏** | 侧栏=任务详情工作台（§5.4）；弹窗中心=跨类型提醒总线（本 L）。任务完成/定时提醒可 **投喂** 消息中心，但列表/详情仍在侧栏 |
| **G 文** | 改为「消息中心 → 2.5.10」，不再写「→2.5」笼统 |

---

### 5.7 Q7 · 并发（已定 A · **2026-09-27 用户改口**：取消抢模型 defer → 多发送窗口）

> **2026-09-27 用户改口：** 否定原「两边同抢模型时聊天优先、后台 Deferred」。改为**同进程多发送窗口**——主 Loop 与后台/Review 可同时调模型；产品门闩**不再**因争模型 defer 新派。执行槽与模型并发**分离**。

| 项 | 决议 |
|----|------|
| 执行槽 1+1 | 前台 **1** 主 Loop **可以同时** 跑后台 **1** Background Run |
| 后台上限 | 全进程同时 **至多 1 个** Background Run（与 Review Worker **合计**一门闩） |
| 前台 | **1 个**主 Loop（同会话 followup 已钉单 RUNNING） |
| 模型多发送窗口 | **同进程内**允许多个模型发送窗口并发（主 Loop ∥ 后台/Review）；**禁止** `Deferred("chat model priority")` / 抢模型探针 |
| 非含义 | **禁止**读成「用户正在聊天 → 后台全停」；也**禁止**把「多发送窗口」读成「无限后台 Run」——执行槽仍 ≤1 bg∪Review |
| Busy/Idle | 只约束**完成结果如何交付**（虚线附带 / 唤模主动发）；**不**禁止后台先执行工具批 |
| Deferred 仍用 | 仅 **concurrency gate**（槽满）；类型保留 |

对标：memory-system 后台并发=1；OpenClaw busy **交付** defer；Claude 前台/后台 subagent 可并存。本仓模型调用技术上本就可并发——产品门闩对齐该事实。

---

### 5.8 Q8 · 旁线（已定 A）

点踩、人物导入、启发式归档 **均不进** 2.5；只 Task 主线 + 约定的 U/L 分期。

---

### 5.9 建议阶段切分（权威以 k05 §4 为准 · 2026-09-26 重切）

```text
2.5.1   规划
2.5.2   类型 + V024 + ScheduleResolver + Repo
2.5.3   Committer + FrozenPlan
2.5.4   执行 + 门闩
2.5.5   Loop / Policy / 用户审核
2.5.6   Busy 虚线交付
2.5.7   Idle 系统唤模
2.5.8   定时模块
2.5.9   侧栏 + 读 API
2.5.10  消息中心
2.5.11  清理
2.6     WORLD_TICK / 生命周期探针
```

## 6. 证据索引（本仓）

- checklist 2.5 · guide/05-agent-loop §12 · guide/03-architecture §TaskRuntime  
- AgentOutcome.java · TurnEngine BackgroundAccepted 分支 · SqliteTurnCommitter taskDraft 拒绝  
- Memory：InProcessMemoryReviewWorker · memory_review_job · MemoryReviewTurnHooks  
- in-flight §8 · world-evolution §4 WORLD_TICK · k04-g「消息中心→2.5」
