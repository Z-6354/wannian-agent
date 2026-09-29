# 归档 · 2.5（Task / BackgroundTask + 用户向定时 · 别名 K05）

`status`: **历史归档 · 周期完成** — 2026-09-29  
`live_cycle`: 现行 → [k06-lifecycle-probe.md](../../k06-lifecycle-probe.md)（**2.6**）  
`checklist`: [实施清单 · 2.5](../../../guide/01-checklist.md)  
`scope`: **2.5.1–2.5.13** 计划 / P2 / 实施单 / draft / **审查证据**

> 本目录只读。WORLD_TICK 真调度属 **2.6**。

## 审查（已迁入）

| 文档 | 内容 |
|------|------|
| [reviews/k05-2.5.13-live-acceptance-20260929.md](./reviews/k05-2.5.13-live-acceptance-20260929.md) | 真人 L1–L8 |
| [reviews/k05-2.5-full-code-review-20260929.md](./reviews/k05-2.5-full-code-review-20260929.md) | 全量审查关闭 |
| [reviews/k05-2.5.7-10-acceptance.md](./reviews/k05-2.5.7-10-acceptance.md) | 2.5.7–10 代审 |
| [reviews/k05-task-system-logic-audit-2.5.md](./reviews/k05-task-system-logic-audit-2.5.md) | 逻辑审阅账本 |

研究决议（历史）：[background-task-peers-2.5.md](../../../research/history/background-task-peers-2.5.md) · [task-schedule-rules-2.5.md](../../../research/history/task-schedule-rules-2.5.md)

## 总览

| 号 | 类型 | 主题 | 入口 |
|----|------|------|------|
| **2.5.1** | 规划 | 周期计划 | [k05-task-background.md](./k05-task-background.md) |
| **2.5.2** | 实现 | 类型 + V024 + Resolver + Repo | [P2](./k05-2.5.2-p2-spec.md) · [实施](./k05-2.5.2-implementation.md) · [draft](./k05-2.5.2-draft/) |
| **2.5.3** | 实现 | Commit 缝 | [P2](./k05-2.5.3-p2-spec.md) · [实施](./k05-2.5.3-implementation.md) · [draft](./k05-2.5.3-draft/) |
| **2.5.4** | 实现 | 执行 + 门闩 | [P2](./k05-2.5.4-p2-spec.md) · [实施](./k05-2.5.4-implementation.md) · [draft](./k05-2.5.4-draft/) |
| **2.5.5** | 实现 | Loop / Policy / 审核 | [P2](./k05-2.5.5-p2-spec.md) · [实施](./k05-2.5.5-implementation.md) · [draft](./k05-2.5.5-draft/) |
| **2.5.6** | 实现 | Busy 虚线交付 | [P2](./k05-2.5.6-p2-spec.md) · [实施](./k05-2.5.6-implementation.md) · [draft](./k05-2.5.6-draft/) |
| **2.5.7** | 实现 | Idle 唤模（+7.2 补丁） | [P2](./k05-2.5.7-p2-spec.md) · [7.2](./k05-2.5.7.2-implementation.md) · [draft](./k05-2.5.7-draft/) |
| **2.5.8** | 实现 | 定时晋升（+8.2 补丁） | [P2](./k05-2.5.8-p2-spec.md) · [8.2](./k05-2.5.8.2-implementation.md) · [draft](./k05-2.5.8-draft/) |
| **2.5.9** | 实现 | 侧栏 + 读 API | [P2](./k05-2.5.9-p2-spec.md) · [实施](./k05-2.5.9-implementation.md) · [draft](./k05-2.5.9-draft/) |
| **2.5.10** | 实现 | 消息中心 | [P2](./k05-2.5.10-p2-spec.md) · [实施](./k05-2.5.10-implementation.md) · [draft](./k05-2.5.10-draft/) |
| **2.5.11** | （暂停） | 原清理号 | [P2](./k05-2.5.11-p2-spec.md) · [实施](./k05-2.5.11-implementation.md) · [draft](./k05-2.5.11-draft/) |
| **2.5.12** | 实现 | 提醒真语义 + UI + 时间 | [P2](./k05-2.5.12-p2-spec.md) · [实施](./k05-2.5.12-implementation.md) · [draft](./k05-2.5.12-draft/) |
| **2.5.13** | 清理 | 收口 | [P2](./k05-2.5.13-p2-spec.md) · [实施](./k05-2.5.13-implementation.md) |

## 书面降级（入档）

| ID | 内容 | 去向 |
|----|------|------|
| E2=B | Memory Review 不查 SubAgent 活跃数；门闩仅约束 Task 侧 | 2.6 可选对称修 |
| E3=C | O4/O6/O8/O9 | 2.6 观察 |
| 2.5.7/2.5.8 | 代审书面降级项 | 见各号实施单与 [7–10 代审](./reviews/k05-2.5.7-10-acceptance.md) |
