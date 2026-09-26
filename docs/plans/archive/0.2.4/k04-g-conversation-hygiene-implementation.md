# 0.2.4-G · 空会话清理与自动归档（LLM 决策）

`status`: **已交付** — 空会话清理 + LLM 归档已入仓；F 收口时修：`create()` 须先 AlreadyExists 再 purge 其它空壳  
`depends`: 0.2.4-B/C/E  
`note`: **禁止改** `wannian-ui/**/*.css`、`layouts/*.css`、`themes/*`、`tokens.css`（样式另轨）。消息系统正文在 **0.2.5**；本阶段仅占位 toast DOM/API。

## 0. 目标

1. **空会话**：无已提交 `message` 的 ACTIVE 会话不算有效会话；点「新会话」或创建前清理其它空会话；启动时清一次历史空会话。
2. **闲置候选**：`last_activity_at`（无则 `created_at`）早于可配置阈值（默认 7 天）的 ACTIVE，进入归档评估队列。
3. **归档决策**：仅经 **可替换端口**；本阶段只接 **大模型实现**。启发式另实现、同接口，后续可配置切换，不耦合进调度器。
4. **调度**：可配置周期（每天 / 每周某日 + 时刻）；若到点未跑过，**启动时补跑**。
5. **用户提示**：归档结果右上角可关闭、可撤销列表（无自动消失）；UI **只加语义 DOM/JS**，不加样式规则。

## 1. 解耦契约

```text
ConversationArchiveEvaluator  (kernel 端口)
  evaluate(ArchiveCandidate) → KEEP | ARCHIVE + reason
       ↑
       ├── LlmConversationArchiveEvaluator     ← 本阶段唯一实现
       └── HeuristicConversationArchiveEvaluator ← 0.2.5+ 可增值，本阶段可不注册
ConversationArchiveScheduler / Settings
  只依赖 Evaluator 接口 + 候选查询 + archive CAS
```

- 候选筛选（闲置天数）属**调度门闩**，不是内容启发式。
- 评估输入：title、titleSource、idleDays、lastActivityAt、最近至多 10 轮用户/助手正文摘要。
- 评估失败 / 无模型 → **KEEP**（不误归档）。

## 2. 空会话

- 定义：`conversation` 下 `message` 行数为 0，且无活动 Turn。
- `purgeEmptyActiveConversations(limit)`：硬删（复用 `purgeOne` 路径），不碰 memory。
- `create` 前执行；`ApplicationRunner` 启动执行。
- 前端「新会话」：先调 purge（或 create 服务端已 purge），再本地清空选中；**首次发送才 create**（避免点一下就落空库）。

## 3. 配置（种子 → wannian.json 可演进）

```yaml
wannian.conversation:
  idle-archive-days: 7
  archive-schedule: DAILY          # DAILY | WEEKLY
  archive-weekday: 1               # WEEKLY 时 1=周一 … 7=周日
  archive-at-hour: 0               # 0–23 本地时区
  archive-at-minute: 0
  archive-batch-limit: 20
  archive-evaluator: llm           # llm | heuristic（启发式未实现时拒绝或回退 KEEP）
```

## 4. 禁止

- 改任何 CSS / theme。
- 把启发式写进 Scheduler 或 LLM 类内部 if-else 混用。
- 完整消息中心（→ 0.2.5）。
- 删除会话级联记忆。

## 5. 验收（窄）

- 连续点「新会话」不增加空 `conversation` 行；库中历史空会话被清。
- 闲置超阈值会话经 LLM 返回 ARCHIVE 后变 ARCHIVED；KEEP 则不变。
- 无模型时不归档。
- 归档后前端出现可撤销占位条（无新 CSS 文件依赖）。
- `Evaluator` 接口可单测 mock；LLM 实现可单独测解析。
