# 2.5 · Task / BackgroundTask + 用户向定时（别名 K05）· 周期计划

`status`: **已交付 · 2.5 周期完成** — 2026-09-29（用户对话确认）  
`version`: **2.5**（施工别名 K05）  
`minor`: **2.5.1 = 本文件（规划计划版）**  
`research`: [background-task-peers-2.5.md](../../../research/history/background-task-peers-2.5.md) · [task-schedule-rules-2.5.md](../../../research/history/task-schedule-rules-2.5.md) · [逻辑审阅 2026-09-28](./reviews/k05-task-system-logic-audit-2.5.md)  
`contract`: [01-contract §6 / §11.2](../../../decisions/01-contract.md) · [04-kernel-reference §Task](../../../guide/04-kernel-reference.md) · [01-checklist §2.5](../../../guide/01-checklist.md)  
`workflow`: [version-stage-workflow.md](../../version-stage-workflow.md) · [wannian-version-workflow](../../../../.agents/skills/wannian-version-workflow/SKILL.md)  
`prerequisite`: **2.1–2.4 已交付**

> **2026-09-26：** 用户锁定 §0.1 / Q3–Q10；并要求**按当前内容重新切分**。旧 P1/P2 与旧 `k05-2.5.2-p2-spec` 作废。  
> **2026-09-27 用户改口（Q7）：** 取消「抢模型时聊天优先、后台 defer」；改为**同进程多发送窗口**（主 Loop 与后台/Review 可同时调模型）。执行槽 1+1（bg Run+Review 合计≤1）**保留**，与模型并发分离。详见 research §5.7 · 2.5.4 P2。  
> **2026-09-28 用户改口：** 本周期纳入提醒真语义 + 任务 UI + 消息时间 → **2.5.12**；清理总结版后移 **2.5.13**；**2.5.11 暂停**（已做代审/软缺口保留）。规格 → [k05-2.5.12-p2-spec.md](./k05-2.5.12-p2-spec.md)。  
> **2026-09-28 投递钉死：** 对标 Codex 收件箱 + DSH follow-up —— **先消息中心（提醒正文）→ 再 Idle/Busy 唤模补一句**；禁止直投写死助手气泡；禁止 placeholder 成功。决议 **D1=C · D2=N · D3=A · D4=A · D5=A**。  
> **2026-09-29：** 真人测通过；计划迁本归档目录。

---

## 0. 一句话目标

模型起草的**后台 Task**不占聊天 Turn：时机（立即/定时）× 重复（单次/多次）正交；**创建须用户审核**；完成后 Busy 虚线附带 / Idle 系统唤模主动发；侧栏可查；消息中心靠后。

### 0.1 产品语义（已锁定）

| 概念 | 定稿 |
|------|------|
| **Task 本质** | 后台执行的任务 |
| **来源** | 模型起草 → Loop → Policy；非互斥「两种 Task」 |
| **正交轴** | 时机（立即/定时）× 重复（单次/多次） |
| **创建审核** | 任务信息发给用户审核；确认后才落库 |
| **Busy 完成** | **交付**规则：结果排队，等当前对话完成后虚线附带；**不**表示后台停跑 |
| **Idle 完成** | **交付**规则：系统唤模，模型主动发消息 |
| **并发（Q7）** | **执行槽 1+1**：前台 1 主 Loop **可同时** 后台 1 Background Run；与 Review 合计后台槽 ≤1。**模型多发送窗口（2026-09-27 用户改口）：** 主 Loop 与后台/Review 可同时调模型，**不**因争模型 defer；Busy≠停后台工具。多窗口 ≠ 无限后台 Run |

---

## 1. 已锁定决议

| ID | 决议 |
|----|------|
| Q1 | Memory Review 不迁；预留 SYSTEM；禁常驻永不终态 |
| Q2 | SubAgentRun = 只读长工具批 |
| Q3 | 时机×重复正交；无通用 create 工具；**创建前用户审核** |
| Q4 | Busy 虚线；Idle 唤模；侧栏 → **2.5.9** |
| Q5 | 用户向定时 → 2.5；WORLD_TICK → 2.6 |
| Q6 | 消息中心 → **2.5.10** |
| Q7 | **1+1 执行槽** + **模型多发送窗口（2026-09-27 用户改口）**：≤1 bg Run（与 Review 合计）+ 1 主 Loop 可并存；模型调用可并发、不因争模型 defer；Busy 只约束交付不禁执行 |
| Q8 | 点踩/人物/启发式归档不进 |
| Q9 | 定时=Task 属性；spec+next_fire+tz；不另建 Cron 表 |
| Q10 | DSL=`relative\|at\|every\|cron`；禁 RRULE 主存；禁裸时长歧义；cron OR 写死 |

---

## 2. 已核实代码事实

1. `BackgroundAccepted` 占位 → `BACKGROUND_NOT_ENABLED`。  
2. `taskDraft` 为 `Object` → unsupported。  
3. 无 background 表；migration 至 V023；本版 V024+。  
4. Review Worker 独立；本版不改。  
5. 同会话主 Loop 单 RUNNING；后台 Run **不占**聊天 Turn，**不得**另开第二用户 Turn 抢 followup。  
6. 无 schedule 列；有进程内 tick 先例。

---

## 3. 不变量

1. 审核通过后，Draft 只经 `CommitTurnPlan` 同事务落库。  
2. SubAgent 不写 Memory/Relationship、不冒充终答。  
3. 重试=新 Run；LOST 不复活。  
4. 取消/完成 CAS 唯一裁决。  
5. 每次执行完成 durable；Idle 必须模型主动消息。  
6. SYSTEM 不 enqueue 真 Job。  
7. WORLD_TICK 仅占位。  
8. 未到期 SCHEDULED 不被执行号抢走；仅定时号晋升。  
9. **1+1 执行槽 + 模型多发送窗口（2026-09-27 用户改口）：** 前台主 Loop 与至多 1 个后台 Run 可并存（与 Review 合计后台槽≤1）；主 Loop 与后台/Review **可同时**调模型，**禁止**因争模型 defer 新派；Busy/Idle 只决定**结果如何交给用户**，不关掉后台执行槽。

---

## 4. 阶段表（重切 · 待 P1）

`p1_status`: **已通过** — 2026-09-26（用户对话确认；采纳 N1–N6）

| 号 | 类型 | 名称 | 交付 | 不做 |
|----|------|------|------|------|
| **2.5.1** | 规划计划版 | 总规格 | 本文件；P1/P2 | 生产码 |
| **2.5.2** | 实现版 | 类型+表+Resolver | kernel.task；schedule_spec；**ScheduleResolver**；V024；Repo；prepare 真（不写库）；其余 Runtime 未启用 | Committer、Executor、Loop、审核、tick、交付 |
| **2.5.3** | 实现版 | Committer+FrozenPlan | Draft 进 CommitTurnPlan；FrozenPlan 升版；同事务 Message+Turn+Outbox+`CREATED`/`SCHEDULED` | Executor、Loop、审核、tick |
| **2.5.4** | 实现版 | 执行+门闩 | LocalTaskExecutor；READY/Run/lease；**后台槽≤1（与 Review 合计）且可与主 Loop 1+1 并存**；**模型多发送窗口**（不因争模型 defer；无 ModelSlotProbe）；**不扫**未到期 SCHEDULED | Policy、审核、交付、tick |
| **2.5.5** | 实现版 | Loop/Policy/**用户审核** | Policy；BackgroundAccepted；**审核确认后才 Commit**；TurnEngine 成功路径 | Idle/Busy 交付、tick、侧栏 |
| **2.5.6** | 实现版 | Busy 虚线交付 | 待合并队列；当前对话完成后虚线附带；载荷协议 | Idle 唤模、tick、侧栏大改 |
| **2.5.7** | 实现版 | Idle 系统唤模 | 无对话时系统唤模主动发；CAS/单 flight | tick、侧栏、消息中心 |
| **2.5.8** | 实现版 | 定时模块 | Ticker；到期晋升→2.5.4；完成后→2.5.6/2.5.7；推进 next_fire；取消 | WORLD_TICK、Cron 表 |
| **2.5.9** | 实现版 | 侧栏+读 API | 读 API；侧栏；虚线渲染；取消 | 消息中心 |
| **2.5.10** | 实现版 | 消息中心 | 弹窗总线；完成/定时/报错投递 | 运营文案库 |
| **2.5.11** | （暂停） | 原清理号 | 代审/软缺口已做；**真人/归档延后** | — |
| **2.5.12** | 实现版 | 提醒真语义+任务 UI+消息时间 | NOTIFY 真投递；去双入口；预览人话；气泡时间 | WORLD_TICK；测文件 |
| **2.5.13** | 清理总结版 | 收口 | 审全部；窄测+真人；归档 | 世界树 |

**依赖：** `1→2→3→4→5→6→7→8→9→10→12→13`（11=暂停桥）  
- 2.5.5 依赖 2.5.3（审核后 Commit）  
- 2.5.8 依赖 2.5.4 + 2.5.6/2.5.7  
- 普通实现版**不**交测文件/真人；仅 **2.5.13** 授权

**证伪拆分：** 2.5.4→执行 vs 门闩；2.5.5→接线 vs 审核 UI；2.5.9→虚线渲染可并入 2.5.6。

### 4.1 schedule_spec（已锁定方向）

| 时机×重复 | 落库 |
|-----------|------|
| 立即×单次 | CREATED；spec=null |
| 定时×单次 | SCHEDULED；relative 或 at |
| 定时×多次 | SCHEDULED；every 或 cron |

| type | 例 |
|------|-----|
| relative | 两天后 `offset:2d` |
| at | 某日某时 ISO |
| every | 每 N 小时 |
| cron | 每周一 / 每月10号 / 每年1月1日 |

### 4.2 本轮 P1 详单（待确认）

#### N1 · 拆类型/表 vs Commit 缝（高）

- **现象：** 原「骨架一把梭」含 DSL+FrozenPlan+Committer，超单轮。  
- **改法：** 2.5.2 类型/表/Resolver；2.5.3 Committer+FrozenPlan。  
- **采纳状态：** **已采纳**

#### N2 · READY vs 未到期（高）

- **改法：** 2.5.4 只跑可执行行；2.5.8 独占到期晋升。  
- **采纳状态：** **已采纳**

#### N3 · 用户审核落点（高）

- **改法：** 2.5.5 独占审核+Policy+接线；未确认不 Commit。  
- **采纳状态：** **已采纳**

#### N4 · Busy / Idle 拆开（中）

- **改法：** 2.5.6 Busy；2.5.7 Idle 唤模；定时依赖两者。  
- **采纳状态：** **已采纳**

#### N5 · 读 API∈侧栏（高）

- **改法：** 2.5.9。  
- **采纳状态：** **已采纳**

#### N6 · 末号后移（低）

- **改法：** 消息中心 2.5.10；清理 2.5.11。  
- **采纳状态：** **已采纳**

```text
[x] 首规划末清理中间实现
[x] N1–N6 已对话确认（2026-09-26）
[x] 覆盖审核/DSL/FrozenPlan/READY/读API/tick/Idle/Busy
[x] 普通号无测文件真人
```

---

## 5. 分阶段要点（≠ P2）

### 5.1 · 2.5.1 规划

P1 确认切分 → P2 逐号细节（2.5.2–2.5.11）。

### 5.2 · 2.5.2 类型+表+Resolver

prepare 组装 Draft（含 spec）；算得 next_fire；**不写库**。

### 5.3 · 2.5.3 Commit 缝

同事务落 CREATED/SCHEDULED；FrozenPlan 含 Draft。

### 5.4 · 2.5.4 执行

可执行行 only；门闩；不扫 SCHEDULED。

### 5.5 · 2.5.5 审核+接线

审核 UI/确认 → Commit；禁假报完成。

### 5.6 · 2.5.6 Busy

对话完成后虚线附带。

### 5.7 · 2.5.7 Idle

系统唤模主动发。

### 5.8 · 2.5.8 定时

Ticker → 晋升 → 交付；推进 next_fire。

### 5.9 · 2.5.9 侧栏

读 API + 侧栏 + 取消。

### 5.10 · 2.5.10 消息中心

弹窗总线。

### 5.11 · 2.5.11 清理

```text
[ ] 规格 vs 代码；子代理审；窄测；真人
[ ] 审核门、同事务、Idle 唤模、Busy 虚线、定时规则、侧栏
[ ] WORLD_TICK/SYSTEM 无误触发；归档
```

---

## 6. 明确不做

世界树 / 第二节点 / Guardian / RemoteTaskExecutor；Review 迁表；通用 create 工具；Steer 乱开 Turn；点踩/人物/归档；Busy 气泡皮肤；WORLD_TICK 真调度；独立 Cron 表；RRULE 主存。

---

## 7. 风险与禁改修法

| 风险 | 禁改修法 |
|------|----------|
| 未审先落库 | 违反 Q3 |
| 先 SSE 再异步 insert | 禁止 |
| Idle 只模板不唤模 | 违反 Q4 |
| 执行号扫未到期 SCHEDULED | 违反不变量 8 |
| 双账本 Cron 表 / RRULE 主存 | 违反 Q9/Q10 |
| 普通号交测文件冒充完成 | 违反 workflow |

---

## 8. 文档义务

- plans/README、roadmap 指向本单  
- research Q 号与阶段表对齐  
- 消息中心表述 → **2.5.10**  
- 清理后勾 checklist

---

## 9. 执行者交付格式

实施单 + MANIFEST → draft 逐文件审 → 代码审 → 缺口先回报。

---

## 10. 开工门

| 门 | 状态 |
|----|------|
| 决议 Q1–Q10 / §0.1 | **已锁定** |
| 重切阶段表 §4 | **已落盘** |
| P1 切分（N1–N6） | **已通过**（2026-09-26） |
| P2 逐号 | **2.5.2–2.5.13 已审/收口** |
| 验收代审 | 2.5.7–2.5.10 结论见 [k05-2.5.7-10-acceptance.md](./reviews/k05-2.5.7-10-acceptance.md) |
| 2.5.11 | **暂停**；证据并入 2.5.13 |
| 2.5.12 | **已验收** |
| 2.5.13 | **①–⑦ 完成 · 周期关闭**（[实施单](./k05-2.5.13-implementation.md) · [live](./reviews/k05-2.5.13-live-acceptance-20260929.md)） |
| 真人 L1–L8 | **通过**（2026-09-29） |
| 归档 | **本目录** `docs/plans/archive/2.5/` |
| 周期门 | **已关闭** — 用户确认「2.5 周期完成」 |

**下一周期：** **2.6**（生命周期探针）。
