# 0.2.3-L · 运行日志 / 行为账本（窄版）

`status`: **已交付**（2026-09-24）；JSONL + turn_step；随 0.2.3 收口  
`version`: **0.2.3** 内部阶段 **L**（Logging / Run Journal）  
`research`: [agent-run-logging-peers.md](../research/agent-run-logging-peers.md)  
`upstream`: [k04-behavior-journal.md](./k04-behavior-journal.md)（本阶段提前落地其核心形状；**不**勾选整批 0.2.4）  
`workflow`: [version-stage-workflow.md](./version-stage-workflow.md) — 普通阶段不交付测文件；既有测因 FK/`turn_step` 已同步修正

---

## 1. 目标

用户要：**每次模型调用的具体内容、每次工具调用、进程启动/关闭**均可事后查阅，便于排障。

| 交付 | 说明 |
|------|------|
| **JSONL 运行日志** | `data/logs/run-YYYY-MM-DD.jsonl` append-only；事件即时写入，失败 Turn 也保留 |
| **SQLite `turn_step`** | 按 turn 可查询的逐步账本；对齐 04-kernel-reference；成功/失败均可留步 |
| **`process_event`** | 进程 START / SHUTDOWN（无 turn FK） |
| **脱敏** | 复用 `ErrorLogFields` / 工具观察脱敏规则；禁止密钥、Authorization 原样入库 |
| **运维通道不变** | `ErrorCodes` / `SafeErrorLog` **不**冒充行为账本 |

---

## 2. 非目标（本阶段）

- Outbox / SSE / `/chat/` 历史恢复（仍属 **0.2.4-B**）
- 管理页华丽审计 UI（可有最小只读查询口或仓储接口即可）
- `MEMORY_WRITE` kind 专项补齐（可随记忆提交自然出现或留给 0.2.4-A）
- **下列已决议、写入计划但本阶段不实施**（见 §6）

---

## 3. 架构（锁死）

```text
DefaultAgentLoop
  → 每 MODEL_CALL / TOOL_CALL 调用 RunJournal.append(...)
       ├─ JsonlRunJournalSink（即时）
       └─ SqliteTurnStepWriter（短事务；turn 已存在 RUNNING）

Spring ApplicationReady / ContextClosed
  → ProcessEventWriter → process_event + JSONL

查询：TurnStepStore.listByTurnId / ProcessEventStore.listRecent
```

**一条语义写入口：** 业务代码只调 `RunJournal` / `ProcessJournal`；禁止各模块 `log.info` JSON 冒充账本。  
**AgentTrace** 仍为内存摘要投影，不得当唯一真源。

### kind / actor

| kind | actor | 载荷要点 |
|------|-------|----------|
| `PROCESS_START` | `system` | buildId、dataDir 摘要、关键配置（无密钥） |
| `PROCESS_SHUTDOWN` | `system` | 原因、uptime |
| `USER_INPUT` | `user` | turnId、inputMessageId、正文摘要/脱敏全文（受配置） |
| `MODEL_CALL` | `agent` | request：messages（可截断）+ tools 名列表；result：outcome 类型、正文/toolCalls、usage、errorCode |
| `TOOL_CALL` | `agent` | request：name + argumentsJson；result：status + observation/error |
| `FINALIZE` | `agent` | 终态（COMPLETED / FAILED / CANCELLED）+ errorCode |

---

## 4. 配置（禁止魔法数散落）

`application.yml`：

```yaml
wannian:
  journal:
    enabled: true
    jsonl-enabled: true
    sqlite-enabled: true
    include-full-messages: true   # false → 只记角色+长度+digest
    max-payload-chars: 65536
```

环境变量前缀：`WANNIAN_JOURNAL_*`。

预算种子（与本阶段并行已改）：`max-model-decisions=10` / soft=`60` / hard=`100`（yml 种子 + 运行期 `wannian.json`）。

---

## 5. 文件清单（生产）

| # | 路径 | 动作 |
|---|------|------|
| 1 | `db/migration/V013__turn_step_journal.sql` | 新建 `turn_step` + `process_event` |
| 2 | `kernel/.../journal/RunJournal*.java` | 端口与条目类型 |
| 3 | `kernel/.../agent/DefaultAgentLoop.java` | 接线 append |
| 4 | `kernel/.../turn/TurnEngine.java` | USER_INPUT / FINALIZE |
| 5 | `app/.../journal/JsonlRunJournal.java` | JSONL |
| 6 | `app/.../journal/SqliteTurnStepJournal.java` | SQLite turn_step |
| 7 | `app/.../journal/CompositeRunJournal.java` | 组合 |
| 8 | `app/.../journal/ProcessEventWriter.java` + `JournalConfig` | 启停 |
| 9 | `app/.../journal/SqliteTurnStepStore.java` | 按 turn 查询 |
| 10 | `app/.../chat/TurnEngineConfig.java` 等 | 装配 |
| 11 | `application.yml` | journal 段 |

普通阶段**不**预写测试类；改坏既有冒烟（如 MigrationSmoke 断言无 `turn_step`）须同步修正。

---

## 6. 系统工具不计决策轮 + 预算错误码分化（**已实施** · 2026-09-24）

### 6.1 系统工具不计决策轮

锁定名单（`countsTowardDecisionBudget=false`）：

- `remember_fact`
- `update_relationship`
- `search_memory`

行为：某一 decide 的 ToolCalls **仅含**上述工具时，**不增加** `completedDecisions`；含任意普通工具（或未知工具）则计入。软/硬截止仍按墙钟，与次数正交。

**知晓（设计如此）**：纯系统工具回合不消耗决策次数，故模型可在 **硬截止之前** 反复 `search_memory` / `remember_fact` / `update_relationship`；唯一硬停是墙钟硬截止（及取消）。本阶段不加「系统工具专用次数上限」；若日后需要再开配置项。

接线：`BuiltinToolPool.Spec` → `ToolRegistration` → `ToolCatalog.CatalogEntry` → `ToolDescriptor` → `DefaultAgentLoop.countsTowardDecisionBudget`。

### 6.2 预算异常错误码分化

| 条件 | code | 用户提示 |
|------|------|----------|
| 决策次数用尽 | `BUDGET_DECISIONS_EXHAUSTED` | 「本轮模型决策次数已用尽」 |
| 软截止 | `BUDGET_SOFT_DEADLINE` | 「已到软截止，不再发起新决策」 |
| 硬截止 | `BUDGET_HARD_DEADLINE` | 「已到硬截止，无法继续调用模型」 |

兼容：旧码 `BUDGET_EXHAUSTED` **仍登记**，新路径不再写出；旧日志/文档可对照。
---

## 7. 验收（阶段代码审用；窄测归版本末段）

```text
[x] 进程启动后 process_event 有 PROCESS_START；正常关闭有 PROCESS_SHUTDOWN（JournalConfig）
[x] data/logs/run-*.jsonl 对含工具的 Turn 出现 MODEL_CALL 与 TOOL_CALL 行（JsonlRunJournal）
[x] 同 turn_id 的 turn_step.step_no 单调；可按 turn 列出（SequencingRunJournal + TurnStepStore）
[x] 失败 Turn（如预算）JSONL/SQLite 仍有已发生的 MODEL/TOOL 步（append-as-you-go）
[x] 载荷走 ErrorLogFields / JournalJson 脱敏；kernel 不依赖 logback
[x] 勾选本阶段清单项；不勾选 0.2.4 完成
[x] 自动化：mvn -pl kernel,app -am test → app 165 项 0 fail（含 V013 migration）
[x] 审计 P1 修复（2026-09-24）：step_no 重启从 MAX 续编；Composite sink 失败打 WARNING；PROCESS_START 读运行期 AgentBudgetSettings；MODEL_CALL 含 tools 名列表；ProcessEventStore.listRecent
[x] 审计 P2 修复（2026-09-24）：AgentInput.conversationId → MODEL/TOOL 行；JournalJson.escape/quote 统一（Jsonl + Loop）；§6.1 注明系统工具可循环至硬截止
```

---

## 8. 与 0.2.4-A 关系

本阶段 = **k04-A 窄版提前**：表 + 单写口 + MODEL/TOOL/进程。  
0.2.4-A 继续：MEMORY_WRITE 策略、与 Outbox 分工文档化、查询/管理面加厚；migration **列兼容**，避免推倒重来。
