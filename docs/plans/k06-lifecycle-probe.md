# 2.6 · 外部生命周期探针（别名 K06）· 周期计划

`status`: **现行周期 · 初版待整理** — 2026-09-29（2.5 已关闭；本文件为活入口）  
`version`: **2.6**（施工别名 K06）  
`minor`: **2.6.1 = 本文件（规划计划版 · 未过 P1）**  
`contract`: [01-contract §探针](../decisions/01-contract.md) · [04-kernel-reference](../guide/04-kernel-reference.md) · [01-checklist §2.6](../guide/01-checklist.md)  
`workflow`: [version-stage-workflow.md](./version-stage-workflow.md) · [wannian-version-workflow](../../.agents/skills/wannian-version-workflow/SKILL.md)  
`prerequisite`: **2.1–2.5 已交付**  
`prior_cycle`: [archive/2.5/](./archive/2.5/README.md)

> **未过 P1 = 不授权写生产码。** 层2：整理文档 → 告知目标 → 检索 → 敲定 → 列出 `2.6.n` 草案表。

---

## 0. 一句话目标（checklist 摘要）

为未来 Guardian 提供最小稳定协议：**live / ready / version / drain**；固定绝对数据目录；**不**实现 Guardian。含 **WORLD_TICK / 世界侧调度相关**边界（不对用户定时推；用户向定时已在 2.5）。

## 1. 含 / 不含（草案，待层2敲定）

| 含（方向） | 不含 |
|------------|------|
| `/internal/live`、`ready`、`version`、`drain` | Guardian 本体 / wn-agent |
| probe DTO；ready 依赖判定 | 世界树、第二节点 |
| drain：停接新 Turn，等现有至终态或超时 | 用户向定时 Task（已 2.5） |
| 两 cwd 同绝对数据路径开同库 | 进程自我替换 / stable·previous 管理 |
| WORLD_TICK 世界侧相关（范围待敲定） | 运营文案库、点踩 |

## 2. 从 2.5 带入的观察项（不自动进范围）

| ID | 来源 | 说明 |
|----|------|------|
| E2 | archive/2.5 | Memory Review 与 bg 门闩对称修（可选） |
| E3/O4–O9 | archive/2.5 | Policy empty / cancel·accept CAS / 交付 hook 观察 |
| WORLD_TICK | 2.5 L7 负向 | 真调度属本周期讨论范围 |

## 3. 小版本草案表（待层2⑤）

| 号 | 类型 | 摘要 | 备注 |
|----|------|------|------|
| **2.6.1** | 规划计划版 | 本文件；P1/P2 | **现行** |
| … | 实现版 | （敲定后填） | |
| **2.6.n** | 清理总结版 | 审+测+真人+归档 | 最大号 |

## 4. 门

| 门 | 状态 |
|----|------|
| 层2 文档整理 / 目标告知 | **进行中**（本文件） |
| P1 切分 | 未过 |
| P2 逐号 | 未过 |
| 写生产码 | **禁止**直至对应号 P2 通过 |

**下一步（对话）：** 层2 告知目标与检索关键词 → 敲定范围 → 列出完整 `2.6.n` 表 → 开 2.6.1 P1。
